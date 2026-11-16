import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** The ONLY place a sample ID is built. Format: MW-S03-20261117-0001 */
public final class SampleId {
    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;

    private SampleId() {}

    public static String prefix(String siteCode, LocalDate date) {
        return "MW-" + siteCode + "-" + D.format(date) + "-";
    }

    public static String create(String siteCode, LocalDate date, int seq) {
        return prefix(siteCode, date) + String.format("%04d", seq);
    }
}
