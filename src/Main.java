import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Entry point: reads input/students.txt, writes output/result_sheet.txt and output/errors.txt. */
public class Main {

    private static final String INPUT_PATH = "input/students.txt";
    private static final String OUTPUT_DIR = "output";
    private static final String RESULT_PATH = OUTPUT_DIR + "/result_sheet.txt";
    private static final String ERROR_PATH = OUTPUT_DIR + "/errors.txt";

    // Run state
    private static final List<Student> students = new ArrayList<>();
    private static final Set<Integer> seenRollNos = new HashSet<>();
    private static int recordsRead = 0;
    private static int accepted = 0;
    private static int rejected = 0;

    public static void main(String[] args) {
        File outDir = new File(OUTPUT_DIR);
        if (!outDir.exists() && !outDir.mkdirs()) {
            System.out.println("Processing failed: cannot create output folder.");
            return;
        }

        try (BufferedWriter errors = new BufferedWriter(new FileWriter(new File(ERROR_PATH), true))) {
            errors.write("=== Run at " + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ===");
            errors.newLine();

            boolean ok = false;
            try {
                File input = checkInputFile(INPUT_PATH);
                readRecords(input, errors);
                ok = true;
            } catch (FileNotFoundException | EmptyInputFileException | InvalidRecordException e) {
                logError(errors, -1, INPUT_PATH, e); // fatal problem with the input file itself
            } catch (IOException e) {
                logError(errors, -1, INPUT_PATH, e);
            }

            if (ok) {
                writeResultSheet();
                System.out.println("Processing complete. See output folder.");
            } else {
                System.out.println("Processing failed. See output/errors.txt.");
            }
        } catch (IOException e) {
            System.out.println("Processing failed: " + e.getMessage());
        }
    }

    /** Verifies the input file exists, is readable and is not empty. */
    private static File checkInputFile(String path) throws FileNotFoundException, EmptyInputFileException {
        File f = new File(path);
        if (!f.exists() || !f.isFile() || !f.canRead()) {
            throw new FileNotFoundException("Input file missing or unreadable: " + path);
        }
        if (f.length() <= 0) {
            throw new EmptyInputFileException("Input file is empty: " + path);
        }
        return f;
    }

    /** Reads the header and up to N record lines, logging every rejected record. */
    private static void readRecords(File file, BufferedWriter errors)
            throws IOException, InvalidRecordException, EmptyInputFileException {
        int expected = -1;
        int extraLines = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                if (expected < 0) { // first meaningful line holds N
                    try {
                        expected = Integer.parseInt(text);
                        if (expected < 0) {
                            throw new NumberFormatException("N must not be negative");
                        }
                    } catch (NumberFormatException e) {
                        throw new InvalidRecordException("Line " + lineNo
                                + ": first line must be a valid record count N but was '" + text + "'", e);
                    }
                    continue;
                }
                if (recordsRead >= expected) {
                    extraLines++;
                    continue;
                }

                recordsRead++;
                try {
                    Student s = parseRecord(text);
                    seenRollNos.add(s.getRollNo());
                    students.add(s);
                    accepted++;
                } catch (InvalidRecordException | InvalidMarksException
                         | InvalidScholarshipException | DuplicateRollNoException e) {
                    rejected++;
                    logError(errors, lineNo, text, e);
                }
            }
        }

        if (expected < 0) {
            throw new EmptyInputFileException("Input file has no header or records: " + file.getPath());
        }
        if (recordsRead < expected) {
            errors.write("NOTE: header declared " + expected + " records but only " + recordsRead + " found.");
            errors.newLine();
        }
        if (extraLines > 0) {
            errors.write("NOTE: " + extraLines + " record line(s) beyond the declared N were ignored.");
            errors.newLine();
        }
    }

    /** Parses one record line into a Student or ScholarshipStudent. */
    private static Student parseRecord(String text)
            throws InvalidRecordException, InvalidMarksException, InvalidScholarshipException {
        String[] f = text.split(",", -1);
        for (int i = 0; i < f.length; i++) {
            f[i] = f[i].trim();
        }

        try {
            String type = f[0].toUpperCase();
            if (!type.equals("R") && !type.equals("S")) {
                throw new InvalidRecordException("Unknown record type '" + f[0] + "'");
            }
            int expectedFields = type.equals("R") ? 6 : 9;
            if (f.length != expectedFields) {
                throw new InvalidRecordException("Type " + type + " requires " + expectedFields
                        + " fields but found " + f.length);
            }

            int rollNo = Integer.parseInt(f[1]);
            String name = f[2];
            if (name.isEmpty()) {
                throw new InvalidRecordException("Student name is empty");
            }
            if (seenRollNos.contains(rollNo)) {
                throw new DuplicateRollNoException("Duplicate roll number " + rollNo);
            }
            double m1 = Double.parseDouble(f[3]);
            double m2 = Double.parseDouble(f[4]);
            double m3 = Double.parseDouble(f[5]);

            if (type.equals("R")) {
                return new Student(rollNo, name, m1, m2, m3);
            }

            double percent = Double.parseDouble(f[6]);
            double bonus = Double.parseDouble(f[7]);
            double fee = Double.parseDouble(f[8]);
            if (Double.isNaN(fee) || Double.isInfinite(fee) || fee < 0.0) {
                throw new InvalidScholarshipException("Annual fee must be a non-negative number but was " + f[8]);
            }
            ScholarshipStudent ss = new ScholarshipStudent(rollNo, name, m1, m2, m3, percent, bonus);
            ss.calculateScholarshipAmount(fee);
            return ss;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new InvalidRecordException("Malformed record: " + e.getMessage(), e);
        }
    }

    /** Writes the full result sheet (overwrites any previous one). */
    private static void writeResultSheet() throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(new File(RESULT_PATH)))) {
            bw.write("RESULT SHEET - " + Student.collegeName);
            bw.newLine();
            bw.write("==================================================");
            bw.newLine();

            for (Student s : students) {
                s.display(bw);
                bw.write("--------------------------------------------------");
                bw.newLine();
            }

            if (students.size() >= 2) {
                Student a = students.get(0);
                Student b = students.get(1);
                bw.write("Higher total between " + a.getName() + " and " + b.getName()
                        + ": " + a.compareTotal(b));
            } else {
                bw.write("Higher total comparison: fewer than two valid students.");
            }
            bw.newLine();

            String topper = Student.findTopper(students.toArray(new Student[0]));
            bw.write("Class topper: " + (topper == null ? "N/A" : topper));
            bw.newLine();
            bw.write("Total students created (studentCount): " + Student.studentCount);
            bw.newLine();
            bw.write("Records read: " + recordsRead + ", accepted: " + accepted
                    + ", rejected: " + rejected + ".");
            bw.newLine();
        }
    }

    /** Writes one error line: line number, record text, exception name, message. */
    private static void logError(BufferedWriter errors, int lineNo, String text, Exception e)
            throws IOException {
        errors.write("Line " + (lineNo < 0 ? "N/A" : String.valueOf(lineNo))
                + " | Record: " + text
                + " | " + e.getClass().getSimpleName()
                + " | " + e.getMessage());
        errors.newLine();
    }
}

/** Checked exception thrown when a mark lies outside the range 0 to 100. */
class InvalidMarksException extends Exception {
    private static final long serialVersionUID = 1L;

    /** The offending value (NaN when no specific value was supplied). */
    private final double rejectedValue;

    public InvalidMarksException(String message) {
        super(message);
        this.rejectedValue = Double.NaN;
    }

    public InvalidMarksException(String message, Throwable cause) {
        super(message, cause);
        this.rejectedValue = Double.NaN;
    }

    public InvalidMarksException(String message, double rejectedValue) {
        super(message);
        this.rejectedValue = rejectedValue;
    }

    /** @return the mark value that was rejected */
    public double getRejectedValue() {
        return rejectedValue;
    }
}

/** Checked exception for an invalid scholarship percent, sports bonus or fee. */
class InvalidScholarshipException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidScholarshipException(String message) {
        super(message);
    }

    public InvalidScholarshipException(String message, Throwable cause) {
        super(message, cause);
    }
}

/** Unchecked exception thrown when a roll number appears more than once. */
class DuplicateRollNoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateRollNoException(String message) {
        super(message);
    }

    public DuplicateRollNoException(String message, Throwable cause) {
        super(message, cause);
    }
}

/** Checked exception for a malformed or unparseable input record. */
class InvalidRecordException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidRecordException(String message) {
        super(message);
    }

    public InvalidRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}

/** Checked exception thrown when the input file has no usable content. */
class EmptyInputFileException extends Exception {
    private static final long serialVersionUID = 1L;

    public EmptyInputFileException(String message) {
        super(message);
    }

    public EmptyInputFileException(String message, Throwable cause) {
        super(message, cause);
    }
}

/** Parent class representing a regular student with three subject marks. */
class Student {

    // ---- Static members ----
    public static String collegeName;
    public static int studentCount = 0;

    static {
        collegeName = "Apex Institute of Technology";
    }

    // ---- Instance variables ----
    private int rollNo;
    private String name;
    private double mark1;
    private double mark2;
    private double mark3;

    // ---- Constructors ----

    /** Default constructor; counts the new student. */
    public Student() {
        studentCount++;
    }

    public Student(int rollNo, String name) {
        this();
        this.rollNo = rollNo;
        this.name = name;
    }

    /**
     * Creates a fully populated student. Marks are validated through the setters.
     * If validation fails the student is not counted.
     */
    public Student(int rollNo, String name, double m1, double m2, double m3)
            throws InvalidMarksException {
        this(rollNo, name);
        try {
            setMark1(m1);
            setMark2(m2);
            setMark3(m3);
        } catch (InvalidMarksException e) {
            studentCount--; // object is discarded, so it must not stay counted
            throw e;
        }
    }

    // ---- Validation helper ----
    private static void checkMark(String label, double m) throws InvalidMarksException {
        if (Double.isNaN(m) || m < 0.0 || m > 100.0) {
            throw new InvalidMarksException(label + " must be between 0 and 100 but was " + m, m);
        }
    }

    // ---- Getters / validated setters ----
    public int getRollNo() { return rollNo; }
    public void setRollNo(int rollNo) { this.rollNo = rollNo; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getMark1() { return mark1; }
    public double getMark2() { return mark2; }
    public double getMark3() { return mark3; }

    public void setMark1(double m1) throws InvalidMarksException {
        checkMark("Mark1", m1);
        this.mark1 = m1;
    }

    public void setMark2(double m2) throws InvalidMarksException {
        checkMark("Mark2", m2);
        this.mark2 = m2;
    }

    public void setMark3(double m3) throws InvalidMarksException {
        checkMark("Mark3", m3);
        this.mark3 = m3;
    }

    // ---- Business methods ----

    /** @return sum of the three marks (max 300) */
    public double calculateTotal() {
        return mark1 + mark2 + mark3;
    }

    /** @return average based on calculateTotal() (polymorphic) */
    public double calculateAverage() {
        return calculateTotal() / 3.0;
    }

    /** @return grade letter from the average */
    public char assignGrade() {
        double avg = calculateAverage();
        if (avg >= 90) return 'A';
        if (avg >= 75) return 'E';
        if (avg >= 60) return 'C';
        if (avg >= 40) return 'D';
        return 'F';
    }

    /** Overload: total plus bonus marks, capped at 300. */
    public double calculateTotal(double bonusMarks) {
        double t = calculateTotal() + bonusMarks;
        return t > 300.0 ? 300.0 : t;
    }

    /** @return name of the student with the higher total; this.name on a tie */
    public String compareTotal(Student other) {
        if (other == null) {
            return this.name;
        }
        return other.calculateTotal() > this.calculateTotal() ? other.name : this.name;
    }

    /** Writes this student's details to the given writer. */
    public void display(BufferedWriter bw) throws IOException {
        bw.write("Roll No      : " + rollNo);
        bw.newLine();
        bw.write("Name         : " + name);
        bw.newLine();
        bw.write("Marks        : " + String.format("%.2f", mark1) + ", "
                + String.format("%.2f", mark2) + ", " + String.format("%.2f", mark3));
        bw.newLine();
        bw.write("Total        : " + String.format("%.2f", calculateTotal()));
        bw.newLine();
        bw.write("Average      : " + String.format("%.2f", calculateAverage()));
        bw.newLine();
        bw.write("Grade        : " + assignGrade());
        bw.newLine();
    }

    /**
     * Finds the student with the highest average (ties: earliest in the array).
     * Null elements are ignored.
     *
     * @return the topper's name, or null if the array has no students
     */
    public static String findTopper(Student[] students) {
        if (students == null) {
            return null;
        }
        Student best = null;
        double bestAvg = -1.0;
        for (Student s : students) {
            if (s == null) {
                continue;
            }
            double avg = s.calculateTotal() / 3.0; // overridden total for scholarship students
            if (best == null || avg > bestAvg) {
                best = s;
                bestAvg = avg;
            }
        }
        return best == null ? null : best.getName();
    }
}

/** Child class: a student who may receive a scholarship and a sports bonus. */
class ScholarshipStudent extends Student {

    private double scholarshipPercent;
    private double sportsBonus;
    private double scholarshipAmount = 0.0;

    public static double minAverageForScholarship = 75.0;

    public ScholarshipStudent() {
        super();
    }

    public ScholarshipStudent(int rollNo, String name, double m1, double m2, double m3,
                              double scholarshipPercent, double sportsBonus)
            throws InvalidMarksException, InvalidScholarshipException {
        // Scholarship values are validated first (inside the super-call argument), so an
        // invalid scholarship never creates or counts a Student object.
        super(validateScholarship(rollNo, scholarshipPercent, sportsBonus), name, m1, m2, m3);
        this.scholarshipPercent = scholarshipPercent;
        this.sportsBonus = sportsBonus;
    }

    /** Validates percent (0-100) and bonus (0-15); returns rollNo unchanged. */
    private static int validateScholarship(int rollNo, double percent, double bonus)
            throws InvalidScholarshipException {
        if (Double.isNaN(percent) || percent < 0.0 || percent > 100.0) {
            throw new InvalidScholarshipException(
                    "Scholarship percent must be between 0 and 100 but was " + percent);
        }
        if (Double.isNaN(bonus) || bonus < 0.0 || bonus > 15.0) {
            throw new InvalidScholarshipException(
                    "Sports bonus must be between 0 and 15 but was " + bonus);
        }
        return rollNo;
    }

    public double getScholarshipPercent() { return scholarshipPercent; }
    public double getSportsBonus() { return sportsBonus; }
    public double getScholarshipAmount() { return scholarshipAmount; }

    /** Total including sports bonus, capped at 300. */
    @Override
    public double calculateTotal() {
        double t = super.calculateTotal() + sportsBonus;
        return t > 300.0 ? 300.0 : t;
    }

    /** Computes, stores and returns the scholarship amount for the given annual fee. */
    public double calculateScholarshipAmount(double annualFee) {
        if (calculateAverage() >= minAverageForScholarship) {
            scholarshipAmount = annualFee * scholarshipPercent / 100.0;
        } else {
            scholarshipAmount = 0.0;
        }
        return scholarshipAmount;
    }

    @Override
    public void display(BufferedWriter bw) throws IOException {
        super.display(bw);
        bw.write("Scholarship %: " + String.format("%.2f", scholarshipPercent));
        bw.newLine();
        bw.write("Sports Bonus : " + String.format("%.2f", sportsBonus));
        bw.newLine();
        bw.write("Scholarship Amount: " + String.format("%.2f", scholarshipAmount));
        bw.newLine();
    }
}
