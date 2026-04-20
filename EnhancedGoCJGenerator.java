package sladeralba;

import java.io.*;
import java.util.*;

// ─── Apache POI imports for Excel (.xlsx) output ───────────────────────────
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * ============================================================
 *  Enhanced GoCJ Dataset Generator — Modified Java Implementation
 *  Based on Algorithm 1 described in:
 *  "Enhanced GoCJ: Google Cloud Jobs Dataset with SLA Classes
 *   and Arrival Times for Distributed & Cloud Computing"
 * ============================================================
 *
 *  HOW TO USE (Two modes):
 *
 *  MODE 1 — Interactive (no arguments):
 *    Run without arguments and the program will:
 *      a) Ask you how many jobs to generate
 *      b) Ask you for the path to the seed .txt file
 *      c) Generate both CSV and XLSX output files
 *
 *  MODE 2 — Command-line arguments (original behaviour kept):
 *    java EnhancedGoCJGenerator <seedFile> <numJobs>
 *    Example: java EnhancedGoCJGenerator Original_Enhanced_Dataset.txt 1000
 *
 *  OUTPUT FILES (written to same folder as seed file, or working dir):
 *    Enhanced_GoCJ_Dataset_<numJobs>.csv   — plain CSV
 *    Enhanced_GoCJ_Dataset_<numJobs>.xlsx  — Excel workbook (3 columns with headers)
 *
 *  COLUMN LAYOUT in both output files:
 *    Column 1  →  Job Length   (Million Instructions, MI)
 *    Column 2  →  SLA Level    (1 = Lowest, 2 = Medium, 3 = Highest)
 *    Column 3  →  Arrival Time (seconds, cumulative Poisson)
 * ============================================================
 */
public class EnhancedGoCJGenerator {

    // ─────────────────────────────────────────────────────────
    //  CONFIGURATION  (mirrors Dataset_Config in Algorithm 1)
    // ─────────────────────────────────────────────────────────

    /** Base Poisson arrival rate λ (jobs per second) */
    private static final double BASE_LAMBDA = 0.62;

    /**
     * Intensity factors — simulate diurnal workload pattern
     *   Business hours  [08:00 – 18:00] → factor = 1.5  (peak)
     *   Off-peak hours                  → factor = 0.7  (quiet)
     */
    private static final double PEAK_INTENSITY    = 1.5;
    private static final double OFFPEAK_INTENSITY = 0.7;

    /** Simulated business-hour window (24-h clock) */
    private static final int BUSINESS_START = 8;
    private static final int BUSINESS_END   = 18;

    /**
     * SLA probability thresholds (cumulative):
     *   SLA-1 (lowest ) : ~18 % of jobs   →  rand < 0.18
     *   SLA-2 (medium ) : ~50 % of jobs   →  rand < 0.68  (0.18 + 0.50)
     *   SLA-3 (highest) : ~32 % of jobs   →  rand ≥ 0.68
     *
     * Matches the paper's observed distribution:
     *   SLA-3 ≈ 32 %, SLA-2 ≈ 50 %, SLA-1 ≈ 18 %
     */
    private static final double SLA1_THRESHOLD = 0.18;
    private static final double SLA2_THRESHOLD = 0.68;   // 0.18 + 0.50

    // ─────────────────────────────────────────────────────────
    //  ALGORITHM STATE  (mirrors Algorithm 1 variable init)
    // ─────────────────────────────────────────────────────────
    private int    cPer             = 0;      // line 6
    private long   jobSize          = 0;      // line 7
    private int    slaClass         = 0;      // line 8
    private double arrivalTime      = 0.0;    // line 9
    private List<long[]> jList      = new ArrayList<>();          // line 10 — Output list
    private TreeMap<Double, Long> dataTable = new TreeMap<>();    // line 11 — cPer → jobSize (kept for Algorithm 1 compatibility)
    private List<Long> seedJobSizes = new ArrayList<>();          // ADDED: flat list of all seed values for direct index pick
    private Random random           = new Random();

    // ─────────────────────────────────────────────────────────
    //  STEP 1 — Load original enhanced dataset into dataTable
    //           (Algorithm 1, lines 20-30)
    // ─────────────────────────────────────────────────────────
    private void loadSeedDataset(String filePath) throws IOException {
        System.out.println("\n[STEP 1] Loading seed dataset from: " + filePath);

        List<Long> jobSizes = new ArrayList<>();
        try (BufferedReader bufferReader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = bufferReader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    try {
                        jobSizes.add(Long.parseLong(line));
                    } catch (NumberFormatException e) {
                        System.out.println("  [SKIP] Non-numeric line: " + line);
                    }
                }
            }
        }

        if (jobSizes.isEmpty()) {
            throw new IllegalArgumentException(
                "ERROR: Seed file is empty or contains no valid job sizes.");
        }

        int    datasetSize          = jobSizes.size();                    // line 23
        double probabilityIncrement = 100.0 / datasetSize;               // line 25

        System.out.println("  → Jobs loaded from seed file : " + datasetSize);
        System.out.println("  → Probability increment      : " +
                           String.format("%.4f", probabilityIncrement));

        // Build cumulative probability table  (lines 28-29 of Algorithm 1)
        cPer = 0;
        for (Long size : jobSizes) {
            dataTable.put((double) cPer, size);
            cPer += (int) Math.round(probabilityIncrement);
        }
        System.out.println("  \u2192 Probability table built with " + dataTable.size() + " entries.");

        // ADDED: flat copy for direct-index bootstrapping.
        // Each of the N seed values has equal probability 1/N of being chosen
        // so every distinct job length in the seed file appears in column 1.
        seedJobSizes = new ArrayList<>(jobSizes);
        System.out.println("  \u2192 Seed list for bootstrapping: " + seedJobSizes.size() + " entries.");
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 2 — Select job size via Monte Carlo Bootstrapping
    //           (Algorithm 1, lines 36-38)
    // ─────────────────────────────────────────────────────────
    private long getJobSize(int rand) {
        // ── FIX: True Monte Carlo bootstrapping with replacement ──────────────
        // The old approach used a TreeMap with keys spaced by probabilityIncrement
        // (e.g. 0, 2, 4 ... for a 50-entry seed file). Because random.nextInt(100)
        // generates only 100 distinct values and the floor-key lookup maps many of
        // them to the same entry, large blocks of output rows ended up with the
        // SAME job length.
        //
        // Correct approach: pick a uniformly random index directly into the flat
        // seedJobSizes list.  Each of the N seed entries then has probability 1/N,
        // exactly matching Monte Carlo bootstrapping with replacement, and EVERY
        // distinct job length in the seed file can appear in the output.
        //
        // The 'rand' parameter (0-99) is still accepted for API compatibility but
        // is replaced by a full-range index pick below.
        if (seedJobSizes == null || seedJobSizes.isEmpty()) {
            // Fallback to TreeMap if seed list somehow not populated
            Double key = dataTable.floorKey((double) rand);
            if (key == null) key = dataTable.firstKey();
            return dataTable.get(key);
        }
        int index = random.nextInt(seedJobSizes.size());   // 0 .. N-1, equal probability
        return seedJobSizes.get(index);
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 3 — Assign SLA class
    //           (Algorithm 1, lines 40-42)
    //
    //  Optional size-based bias (Section 4.2 of the paper):
    //    Huge / Extra-Large jobs are nudged toward SLA-3.
    //    Small jobs are nudged toward SLA-1.
    // ─────────────────────────────────────────────────────────
    private int assignSLAClass(double slaRand, long jobSz) {

        // Apply weak size-based bias (paper Section 4.2)
        if (jobSz >= 525000) {
            // Huge jobs — bias toward SLA-3 (mission-critical)
            slaRand = Math.max(0.0, slaRand - 0.25);
        } else if (jobSz >= 150000) {
            // Extra-Large — slight bias toward SLA-3
            slaRand = Math.max(0.0, slaRand - 0.10);
        } else if (jobSz <= 55000) {
            // Small jobs — slight bias toward SLA-1 (best-effort)
            slaRand = Math.min(1.0, slaRand + 0.10);
        }

        // Threshold-based mapping
        if      (slaRand < SLA1_THRESHOLD) return 1;   // ~18% → SLA-1 (Lowest)
        else if (slaRand < SLA2_THRESHOLD) return 2;   // ~50% → SLA-2 (Medium)
        else                               return 3;   // ~32% → SLA-3 (Highest)
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 4 — Generate arrival time via non-homogeneous
    //           Poisson process
    //           (Algorithm 1, lines 44-48)
    // ─────────────────────────────────────────────────────────
    private double getIntensityFactor(double currentArrivalTime) {
        // Derive simulated hour from running arrival time (seconds)
        int secondsInDay  = (int)(currentArrivalTime % 86400);
        int simulatedHour = secondsInDay / 3600;

        if (simulatedHour >= BUSINESS_START && simulatedHour < BUSINESS_END) {
            return PEAK_INTENSITY;      // Business hours → higher arrival rate
        } else {
            return OFFPEAK_INTENSITY;   // Off-peak → lower arrival rate
        }
    }

    // ─────────────────────────────────────────────────────────
    //  MAIN GENERATION LOOP
    //  Implements Algorithm 1: while (num ≥ a) loop at lines 34-53
    // ─────────────────────────────────────────────────────────
    private void generateDataset(int numJobs) {
        System.out.println("\n[STEP 2] Generating " + numJobs + " enhanced jobs ...");
        System.out.println("         (Running Algorithm 1: Monte Carlo Bootstrapping)");

        int a = 1;              // Loop counter        (line 33)
        arrivalTime = 0.0;      // Reset arrival time  (line 34)
        jList.clear();

        while (numJobs >= a) {  // line 35

            // ── Step 1: Select job size (Monte Carlo Bootstrapping) ── line 36-38
            int rand = random.nextInt(100);           // line 37
            jobSize  = getJobSize(rand);              // line 38

            // ── Step 2: Assign SLA class ── lines 40-42
            double slaRand = random.nextDouble();                    // line 41
            slaClass = assignSLAClass(slaRand, jobSize);            // line 42

            // ── Step 3: Generate arrival time (Poisson process) ── lines 44-48
            double intensityFactor = getIntensityFactor(arrivalTime);   // line 45
            double effectiveLambda = BASE_LAMBDA * intensityFactor;     // line 46
            double interArrival    = -Math.log(random.nextDouble())     // line 47
                                      / effectiveLambda;
            arrivalTime = arrivalTime + interArrival;                   // line 48

            // ── Step 4: Create enhanced job record ── lines 50-52
            long roundedArrival = Math.round(arrivalTime);              // line 51
            jList.add(new long[]{jobSize, slaClass, roundedArrival});   // line 52

            a++;   // line 53
        }

        System.out.println("  → Generation complete. Total jobs produced: " + jList.size());
    }

    // ─────────────────────────────────────────────────────────
    //  WRITE OUTPUT CSV
    //  Format: jobLength,slaClass,arrivalTime  (no header)
    //  Produces exactly numJobs rows × 3 columns
    // ─────────────────────────────────────────────────────────
    private String writeOutputCSV(int numJobs, String outputDir) throws IOException {
        String outputFile = outputDir + File.separator
                          + "Enhanced_GoCJ_Dataset_" + numJobs + ".csv";
        System.out.println("\n[STEP 3a] Writing CSV  → " + outputFile);

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            for (long[] job : jList) {
                // Column 1: Job Length (MI)
                // Column 2: SLA Level  (1/2/3)
                // Column 3: Arrival Time (seconds)
                writer.println(job[0] + "," + job[1] + "," + job[2]);
            }
        }
        System.out.println("  → CSV written successfully  (" + jList.size() + " rows, 3 columns).");
        return outputFile;
    }

    // ─────────────────────────────────────────────────────────
    //  WRITE OUTPUT XLSX  (Apache POI)
    //  Produces same data as CSV but in Excel format with headers.
    //  Column A: Job Length (MI)
    //  Column B: SLA Level
    //  Column C: Arrival Time (s)
    // ─────────────────────────────────────────────────────────
    private String writeOutputXLSX(int numJobs, String outputDir) throws IOException {
        String outputFile = outputDir + File.separator
                          + "Enhanced_GoCJ_Dataset_" + numJobs + ".xlsx";
        System.out.println("\n[STEP 3b] Writing XLSX → " + outputFile);

        try (Workbook workbook = new XSSFWorkbook()) {

            Sheet sheet = workbook.createSheet("GoCJ_Dataset_" + numJobs);

            // ── Header row (row 0) ──────────────────────────────────
            Row header = sheet.createRow(0);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            // Add thin border to header
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            String[] headers = {"Job Length (MI)", "SLA Level (1/2/3)", "Arrival Time (s)"};
            for (int col = 0; col < headers.length; col++) {
                Cell cell = header.createCell(col);
                cell.setCellValue(headers[col]);
                cell.setCellStyle(headerStyle);
            }

            // ── Data rows (rows 1..numJobs) ────────────────────────
            // Style for SLA-1
            CellStyle sla1Style = workbook.createCellStyle();
            sla1Style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            sla1Style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sla1Style.setAlignment(HorizontalAlignment.CENTER);

            // Style for SLA-2
            CellStyle sla2Style = workbook.createCellStyle();
            sla2Style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            sla2Style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sla2Style.setAlignment(HorizontalAlignment.CENTER);

            // Style for SLA-3
            CellStyle sla3Style = workbook.createCellStyle();
            sla3Style.setFillForegroundColor(IndexedColors.CORAL.getIndex());
            sla3Style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sla3Style.setAlignment(HorizontalAlignment.CENTER);

            // Default (number) style for columns 1 & 3
            CellStyle numStyle = workbook.createCellStyle();
            numStyle.setAlignment(HorizontalAlignment.RIGHT);

            int rowIdx = 1;
            for (long[] job : jList) {
                Row row     = sheet.createRow(rowIdx++);

                // Column A — Job Length
                Cell cLen   = row.createCell(0);
                cLen.setCellValue(job[0]);
                cLen.setCellStyle(numStyle);

                // Column B — SLA Level  (colour-coded by tier)
                Cell cSla   = row.createCell(1);
                cSla.setCellValue(job[1]);
                switch ((int) job[1]) {
                    case 1:  cSla.setCellStyle(sla1Style); break;
                    case 2:  cSla.setCellStyle(sla2Style); break;
                    default: cSla.setCellStyle(sla3Style); break;
                }

                // Column C — Arrival Time
                Cell cArr   = row.createCell(2);
                cArr.setCellValue(job[2]);
                cArr.setCellStyle(numStyle);
            }

            // Auto-size all 3 columns for readability
            for (int col = 0; col < 3; col++) {
                sheet.autoSizeColumn(col);
            }

            // Write workbook to file
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                workbook.write(fos);
            }
        }

        System.out.println("  → XLSX written successfully (" + jList.size()
                         + " data rows + 1 header row, 3 columns).");
        return outputFile;
    }

    // ─────────────────────────────────────────────────────────
    //  PRINT STATISTICS  (validation after generation)
    // ─────────────────────────────────────────────────────────
    private void printStatistics() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  DATASET STATISTICS  (Validation)");
        System.out.println("=".repeat(60));

        long   minLen = Long.MAX_VALUE, maxLen = Long.MIN_VALUE;
        double sumLen = 0;
        long   maxArr = 0;
        double sumArr = 0;

        List<Long> lengths = new ArrayList<>();

        for (long[] job : jList) {
            long len = job[0];
            long arr = job[2];

            lengths.add(len);
            sumLen += len;
            sumArr += arr;
            if (len < minLen) minLen = len;
            if (len > maxLen) maxLen = len;
            if (arr > maxArr) maxArr = arr;
        }

        Collections.sort(lengths);
        long   median  = lengths.get(lengths.size() / 2);
        double avgLen  = sumLen / jList.size();
        double avgArr  = sumArr / jList.size();

        System.out.printf("  Total Jobs         : %d%n",    jList.size());
        System.out.printf("  Min Length   (MI)  : %,d%n",   minLen);
        System.out.printf("  Max Length   (MI)  : %,d%n",   maxLen);
        System.out.printf("  Avg Length   (MI)  : %,.0f%n", avgLen);
        System.out.printf("  Median       (MI)  : %,d%n",   median);
        System.out.printf("  Max Arrival  (s)   : %,d%n",   maxArr);
        System.out.printf("  Avg Arrival  (s)   : %.2f%n",  avgArr);
        System.out.println("=".repeat(60));
    }

    // ─────────────────────────────────────────────────────────
    //  INTERACTIVE INPUT HELPER
    //  Asks the user:
    //    1) How many jobs to generate
    //    2) Path to the seed .txt file
    // ─────────────────────────────────────────────────────────
    private static int[] askUserForParams(Scanner scanner, String[] prefillArgs) {
        // returns int[0] = numJobs  (we handle seedFile separately via String)
        return null; // not used, kept for readability — logic is inlined in main()
    }

    // ─────────────────────────────────────────────────────────
    //  ENTRY POINT
    // ─────────────────────────────────────────────────────────
    public static void main(String[] args) {

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║       Enhanced GoCJ Dataset Generator v1.0           ║");
        System.out.println("║  Algorithm 1 — Java Implementation                   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        String seedFile;
        int    numJobs;

        Scanner scanner = new Scanner(System.in);

        // ── Determine mode: interactive vs. command-line ─────────────────────
        if (args.length >= 2) {
            // ── MODE 2: command-line arguments (original behaviour) ──────────
            seedFile = args[0];
            try {
                numJobs = Integer.parseInt(args[1]);
                if (numJobs <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                System.out.println("ERROR: <numJobs> must be a positive integer.");
                System.exit(1);
                return;
            }
            System.out.println("\n  Mode            : Command-line");
            System.out.println("  Seed file       : " + seedFile);
            System.out.println("  Jobs to generate: " + numJobs);

        } else {
            // ── MODE 1: interactive — ask the user ───────────────────────────
            System.out.println();
            System.out.println("  ┌─────────────────────────────────────────────┐");
            System.out.println("  │         Interactive Dataset Generator        │");
            System.out.println("  └─────────────────────────────────────────────┘");

            // ── Ask for number of jobs ────────────────────────────────────────
            numJobs = 0;
            while (numJobs <= 0) {
                System.out.print("\n  How many jobs do you want to generate? (e.g. 1000 / 2000 / 3000): ");
                String input = scanner.nextLine().trim();
                try {
                    numJobs = Integer.parseInt(input);
                    if (numJobs <= 0) {
                        System.out.println("  ✗  Please enter a positive integer greater than 0.");
                        numJobs = 0;
                    } else {
                        System.out.println("  ✓  Will generate " + numJobs + " jobs.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("  ✗  Invalid number '" + input + "'. Please try again.");
                }
            }

            // ── Ask for seed file path ────────────────────────────────────────
            seedFile = "";
            while (seedFile.isEmpty()) {
                System.out.println();
                System.out.println("  Enter the full path to the seed dataset file.");
                System.out.println("  (The file should contain one job size per line in MI)");
                System.out.println("  Example paths:");
                System.out.println("    Windows : D:\\Cloud\\dataset\\sladeralba\\Original_Enhanced_Dataset.txt");
                System.out.println("    Linux   : /home/user/data/Original_Enhanced_Dataset.txt");
                System.out.print("\n  Seed file path: ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    System.out.println("  ✗  Path cannot be empty. Please try again.");
                    continue;
                }

                File f = new File(input);
                if (!f.exists()) {
                    System.out.println("  ✗  File not found at: " + input);
                    System.out.println("     Please check the path and try again.");
                } else if (!f.isFile()) {
                    System.out.println("  ✗  Path exists but is not a file: " + input);
                } else {
                    seedFile = input;
                    System.out.println("  ✓  File found: " + f.getAbsolutePath());
                }
            }

            System.out.println();
            System.out.println("  ─────────────────────────────────────────────────");
            System.out.println("  Summary of your request:");
            System.out.println("    Jobs to generate : " + numJobs);
            System.out.println("    Seed file        : " + seedFile);
            System.out.println("  ─────────────────────────────────────────────────");
        }

        // ── Determine output directory (same folder as seed file) ────────────
        File   seedFileObj = new File(seedFile);
        String outputDir   = seedFileObj.getParent() != null
                           ? seedFileObj.getParent()
                           : ".";   // fallback: current working directory

        System.out.println("\n  Output directory : " + outputDir);
        System.out.println("\n  Starting dataset generation...");
        System.out.println("  ═".repeat(30));

        // ── Run the generator ────────────────────────────────────────────────
        EnhancedGoCJGenerator generator = new EnhancedGoCJGenerator();
        try {
            // Algorithm 1, lines 20-30 — load seed dataset
            generator.loadSeedDataset(seedFile);

            // Algorithm 1, lines 33-53 — generate dataset
            generator.generateDataset(numJobs);

            // Write CSV output
            String csvPath = generator.writeOutputCSV(numJobs, outputDir);

            // Write XLSX output  (requires Apache POI on classpath)
            String xlsxPath = null;
            try {
                xlsxPath = generator.writeOutputXLSX(numJobs, outputDir);
            } catch (NoClassDefFoundError | Exception poiEx) {
                System.out.println("\n  [WARNING] Apache POI not available on classpath.");
                System.out.println("            XLSX output skipped. CSV output is still complete.");
                System.out.println("            Add poi-ooxml.jar to your build path to enable XLSX.");
            }

            // Print validation statistics
            generator.printStatistics();

            // ── Final summary ─────────────────────────────────────────────────
            System.out.println();
            System.out.println("  ╔════════════════════════════════════════════════════════════════╗");
            System.out.println("  ║                      GENERATION COMPLETE                       ║");
            System.out.println("  ╠════════════════════════════════════════════════════════════════╣");
            System.out.printf ("  ║  Total rows generated : %-39d║%n", generator.jList.size());
            System.out.printf ("  ║  Columns per row      : %-39s║%n", "3 (Job Length, SLA Level, Arrival Time)");
            System.out.println("  ╠════════════════════════════════════════════════════════════════╣");
            System.out.printf ("  ║  CSV  file : %-50s║%n",
                               truncate(csvPath, 50));
            if (xlsxPath != null) {
                System.out.printf("  ║  XLSX file : %-50s║%n",
                               truncate(xlsxPath, 50));
            }
            System.out.println("  ╚════════════════════════════════════════════════════════════════╝");

        } catch (FileNotFoundException e) {
            System.out.println("\nERROR: Seed file not found → " + seedFile);
        } catch (IOException e) {
            System.out.println("\nERROR: I/O problem → " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("\n" + e.getMessage());
        } finally {
            scanner.close();
        }
    }

    // ─────────────────────────────────────────────────────────
    //  UTILITY — truncate long paths for display
    // ─────────────────────────────────────────────────────────
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return "..." + s.substring(s.length() - (maxLen - 3));
    }
}