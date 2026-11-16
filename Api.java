import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * MicroWatch API - plain Java, no frameworks.
 *
 *   GET  /api/health
 *   GET  /api/sites                 POST /api/sites
 *   GET  /api/samples               POST /api/samples
 *   GET  /api/samples/{id}
 *   POST /api/samples/{id}/steps/{1|2|3}/complete
 *
 * Settings (environment variables):
 *   DB_URL, DB_USER, DB_PASS   database connection
 *   HOST (default 127.0.0.1), PORT (default 8080)
 */
public class Api {

    private static String dbUrl;
    private static String dbUser;
    private static String dbPass;

    private static final Pattern SAMPLE_ID = Pattern.compile("MW-[A-Za-z0-9]{1,10}-\\d{8}-\\d{4}");
    private static final Pattern SITE_CODE = Pattern.compile("[A-Za-z0-9]{1,10}");
    private static final List<String> POSITIONS = Arrays.asList("upstream", "downstream", "other");
    private static final String[] STEP_NAMES = {"Collection", "Filtration", "Imaging"};
    private static final int MAX_BODY = 10000;

    // ------------------------------------------------------------------ server

    public static void main(String[] args) throws Exception {
        dbUrl = env("DB_URL", "jdbc:mysql://localhost:3306/microwatch");
        dbUser = env("DB_USER", "root");
        dbPass = env("DB_PASS", "");
        String host = env("HOST", "127.0.0.1"); // local only by default
        int port = Integer.parseInt(env("PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/", Api::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("MicroWatch API running on http://" + host + ":" + port + "/api/health");
    }

    static final class ApiException extends RuntimeException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class Result {
        final int status;
        final Object body;

        Result(int status, Object body) {
            this.status = status;
            this.body = body;
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        int status;
        Object body;
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // dev only; restrict later
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            Result r = route(ex.getRequestMethod(), ex);
            status = r.status;
            body = r.body;
        } catch (ApiException e) {
            status = e.status;
            body = map("error", e.getMessage());
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            status = 500;
            body = map("error", "Database error");
        } catch (Exception e) {
            e.printStackTrace();
            status = 500;
            body = map("error", "Server error");
        }
        System.out.println(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " -> " + status);
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Result route(String method, HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        String[] p = path.length() <= 5 ? new String[0] : path.substring(5).split("/");
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");

        if (p.length == 1 && p[0].equals("health") && get) {
            return new Result(200, map("status", "ok"));
        }
        if (p.length == 1 && p[0].equals("sites")) {
            if (get) return new Result(200, listSites());
            if (post) return new Result(201, createSite(readJson(ex)));
        }
        if (p.length >= 1 && p[0].equals("samples")) {
            if (p.length == 1 && get) return new Result(200, listSamples());
            if (p.length == 1 && post) return new Result(201, createSample(readJson(ex)));
            if (p.length == 2 && get) return new Result(200, getSample(checkId(p[1])));
            if (p.length == 5 && post && p[2].equals("steps") && p[4].equals("complete")) {
                return new Result(200, completeStep(checkId(p[1]), parseStep(p[3]), readJson(ex)));
            }
        }
        throw new ApiException(404, "Not found");
    }

    // ------------------------------------------------------------------ sites

    private static Object listSites() throws SQLException {
        List<Object> out = new ArrayList<Object>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT site_id, code, name, latitude, longitude, position FROM sites ORDER BY code");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map("site_id", rs.getInt(1), "code", rs.getString(2), "name", rs.getString(3),
                        "latitude", rs.getBigDecimal(4), "longitude", rs.getBigDecimal(5),
                        "position", rs.getString(6)));
            }
        }
        return out;
    }

    private static Object createSite(Map<String, Object> in) throws SQLException {
        String code = str(in, "code", true, 10).toUpperCase();
        if (!SITE_CODE.matcher(code).matches()) throw new ApiException(400, "code must be letters/digits, max 10");
        String name = str(in, "name", true, 100);
        BigDecimal lat = num(in, "latitude");
        BigDecimal lon = num(in, "longitude");
        if (lat.compareTo(new BigDecimal("-90")) < 0 || lat.compareTo(new BigDecimal("90")) > 0)
            throw new ApiException(400, "latitude must be between -90 and 90");
        if (lon.compareTo(new BigDecimal("-180")) < 0 || lon.compareTo(new BigDecimal("180")) > 0)
            throw new ApiException(400, "longitude must be between -180 and 180");
        String position = in.get("position") == null ? "other" : str(in, "position", true, 20);
        if (!POSITIONS.contains(position)) throw new ApiException(400, "position must be upstream, downstream or other");

        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sites(code, name, latitude, longitude, position) VALUES(?,?,?,?,?)")) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setBigDecimal(3, lat);
            ps.setBigDecimal(4, lon);
            ps.setString(5, position);
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ApiException(409, "A site with code " + code + " already exists");
        }
        return map("code", code, "name", name, "latitude", lat, "longitude", lon, "position", position);
    }

    // ------------------------------------------------------------------ samples

    private static Object createSample(Map<String, Object> in) throws SQLException {
        String siteCode = str(in, "site_code", true, 10).toUpperCase();
        if (!SITE_CODE.matcher(siteCode).matches()) throw new ApiException(400, "Invalid site_code");
        String source = str(in, "water_source", false, 100);
        int volume = intVal(in, "volume_ml");
        if (volume <= 0 || volume > 100000) throw new ApiException(400, "volume_ml must be between 1 and 100000");

        for (int attempt = 0; attempt < 5; attempt++) {
            try (Connection c = open()) {
                c.setAutoCommit(false);
                try {
                    int siteId = findSite(c, siteCode);
                    LocalDate today = LocalDate.now();
                    String id = SampleId.create(siteCode, today, nextSeq(c, siteCode, today));

                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO samples(sample_id, site_id, water_source, volume_ml, collected_at) VALUES(?,?,?,?,?)")) {
                        ps.setString(1, id);
                        ps.setInt(2, siteId);
                        ps.setString(3, source);
                        ps.setInt(4, volume);
                        ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO sample_steps(sample_id, step_no, step_name) VALUES(?,?,?)")) {
                        for (int i = 0; i < STEP_NAMES.length; i++) {
                            ps.setString(1, id);
                            ps.setInt(2, i + 1);
                            ps.setString(3, STEP_NAMES[i]);
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                    return getSample(id);
                } catch (SQLIntegrityConstraintViolationException dup) {
                    c.rollback(); // someone else took this ID; try again with the next number
                }
            }
        }
        throw new ApiException(503, "Could not allocate a sample ID, please retry");
    }

    private static int findSite(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT site_id FROM sites WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Unknown site_code " + code + " (create it with POST /api/sites)");
                return rs.getInt(1);
            }
        }
    }

    private static int nextSeq(Connection c, String siteCode, LocalDate day) throws SQLException {
        String prefix = SampleId.prefix(siteCode, day);
        try (PreparedStatement ps = c.prepareStatement("SELECT MAX(sample_id) FROM samples WHERE sample_id LIKE ?")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String last = rs.getString(1);
                if (last == null) return 1;
                int next = Integer.parseInt(last.substring(last.length() - 4)) + 1;
                if (next > 9999) throw new ApiException(409, "Daily sample limit reached for this site");
                return next;
            }
        }
    }

    private static Object listSamples() throws SQLException {
        List<Object> out = new ArrayList<Object>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT s.sample_id, st.code, s.collected_at, s.referral_status, "
                             + "(SELECT COUNT(*) FROM sample_steps x WHERE x.sample_id = s.sample_id AND x.status = 'done') "
                             + "FROM samples s JOIN sites st ON st.site_id = s.site_id "
                             + "ORDER BY s.collected_at DESC, s.sample_id DESC LIMIT 50");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map("sample_id", rs.getString(1), "site_code", rs.getString(2),
                        "collected_at", rs.getTimestamp(3), "referral_status", rs.getString(4),
                        "steps_done", rs.getInt(5)));
            }
        }
        return out;
    }

    private static Object getSample(String id) throws SQLException {
        try (Connection c = open()) {
            Map<String, Object> m;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.sample_id, st.code, st.name, s.water_source, s.volume_ml, s.collected_at, s.referral_status "
                            + "FROM samples s JOIN sites st ON st.site_id = s.site_id WHERE s.sample_id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(404, "Sample " + id + " not found");
                    m = map("sample_id", rs.getString(1), "site_code", rs.getString(2), "site_name", rs.getString(3),
                            "water_source", rs.getString(4), "volume_ml", rs.getInt(5),
                            "collected_at", rs.getTimestamp(6), "referral_status", rs.getString(7));
                }
            }
            List<Object> steps = new ArrayList<Object>();
            int done = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT step_no, step_name, status, notes, completed_at FROM sample_steps "
                            + "WHERE sample_id = ? ORDER BY step_no")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getString(3).equals("done")) done++;
                        steps.add(map("step_no", rs.getInt(1), "step_name", rs.getString(2), "status", rs.getString(3),
                                "notes", rs.getString(4), "completed_at", rs.getTimestamp(5)));
                    }
                }
            }
            m.put("steps", steps);
            m.put("all_steps_done", done == STEP_NAMES.length);
            return m;
        }
    }

    /** Marks a step done. Steps must be completed in order: 1, then 2, then 3. */
    private static Object completeStep(String id, int step, Map<String, Object> in) throws SQLException {
        String notes = str(in, "notes", false, 255);
        try (Connection c = open()) {
            c.setAutoCommit(false);
            String current;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status FROM sample_steps WHERE sample_id = ? AND step_no = ? FOR UPDATE")) {
                ps.setString(1, id);
                ps.setInt(2, step);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(404, "Sample " + id + " not found");
                    current = rs.getString(1);
                }
            }
            if (current.equals("done")) throw new ApiException(409, "Step " + step + " is already done");
            if (step > 1) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT status FROM sample_steps WHERE sample_id = ? AND step_no = ?")) {
                    ps.setString(1, id);
                    ps.setInt(2, step - 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !rs.getString(1).equals("done"))
                            throw new ApiException(409, "Step " + (step - 1) + " (" + STEP_NAMES[step - 2] + ") must be completed first");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sample_steps SET status = 'done', notes = ?, completed_at = ? WHERE sample_id = ? AND step_no = ?")) {
                ps.setString(1, notes);
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                ps.setString(3, id);
                ps.setInt(4, step);
                ps.executeUpdate();
            }
            c.commit();
        }
        return getSample(id);
    }

    // ------------------------------------------------------------------ helpers

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPass);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null ? fallback : v;
    }

    private static String checkId(String id) {
        if (!SAMPLE_ID.matcher(id).matches()) throw new ApiException(400, "Invalid sample ID");
        return id;
    }

    private static int parseStep(String s) {
        try {
            int n = Integer.parseInt(s);
            if (n >= 1 && n <= 3) return n;
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw new ApiException(400, "Step must be 1, 2 or 3");
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) throw new ApiException(413, "Request too large");
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new LinkedHashMap<String, Object>();
        return new JsonReader(text).readObject();
    }

    static String str(Map<String, Object> m, String key, boolean required, int max) {
        Object v = m.get(key);
        if (v == null) {
            if (required) throw new ApiException(400, key + " is required");
            return null;
        }
        if (!(v instanceof String)) throw new ApiException(400, key + " must be text");
        String t = ((String) v).trim();
        if (t.isEmpty()) {
            if (required) throw new ApiException(400, key + " is required");
            return null;
        }
        if (t.length() > max) throw new ApiException(400, key + " is too long (max " + max + ")");
        return t;
    }

    static BigDecimal num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new ApiException(400, key + " is required");
        if (!(v instanceof BigDecimal)) throw new ApiException(400, key + " must be a number");
        return (BigDecimal) v;
    }

    static int intVal(Map<String, Object> m, String key) {
        try {
            return num(m, key).intValueExact();
        } catch (ArithmeticException e) {
            throw new ApiException(400, key + " must be a whole number");
        }
    }

    // ------------------------------------------------------------------ minimal JSON

    /** Reads one flat JSON object (text, numbers, true/false/null). Nested values are rejected. */
    static final class JsonReader {
        private final String s;
        private int i = 0;

        JsonReader(String s) {
            this.s = s;
        }

        private ApiException err() {
            return new ApiException(400, "Invalid JSON");
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (i >= s.length()) throw err();
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) throw err();
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            ws();
            expect('{');
            ws();
            if (peek() == '}') {
                i++;
            } else {
                while (true) {
                    ws();
                    String k = readString();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, readScalar());
                    ws();
                    char c = next();
                    if (c == ',') continue;
                    if (c == '}') break;
                    throw err();
                }
            }
            ws();
            if (i != s.length()) throw err();
            return m;
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err();
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err();
                            }
                            i += 4;
                            break;
                        default: throw err();
                    }
                } else if (c < 0x20) {
                    throw err();
                } else {
                    sb.append(c);
                }
            }
        }

        private Object readScalar() {
            char c = peek();
            if (c == '"') return readString();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '-' || Character.isDigit(c)) {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                try {
                    return new BigDecimal(s.substring(start, i));
                } catch (NumberFormatException e) {
                    throw err();
                }
            }
            throw err();
        }
    }

    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote(sb, (String) o);
        } else if (o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof BigDecimal) {
            sb.append(((BigDecimal) o).toPlainString());
        } else if (o instanceof Number) {
            sb.append(o);
        } else if (o instanceof Timestamp) {
            quote(sb, ((Timestamp) o).toLocalDateTime().toString());
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object x : (List<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
