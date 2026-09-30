import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class GeneScanServer {

    private static final int PORT = 8081;
    private static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024 + 64 * 1024;
    private static final int MAX_SEQUENCE_LENGTH = 5_000_000;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/api/health", GeneScanServer::handleHealth);
        server.createContext("/api/extract", GeneScanServer::handleExtractText);
        server.createContext("/api/extract-file", GeneScanServer::handleExtractFile);
        server.createContext("/api/analyze", GeneScanServer::handleAnalyze);
        server.createContext("/api/analyze-file", GeneScanServer::handleAnalyzeFile);
        server.createContext("/api/highlight-file", GeneScanServer::handleHighlightFile);

        server.setExecutor(null);
        server.start();

        System.out.println();
        System.out.println("==============================================");
        System.out.println("          GENESCAN JAVA API SERVER");
        System.out.println("==============================================");
        System.out.println("Server running on:");
        System.out.println("http://localhost:" + PORT);
        System.out.println();
        System.out.println("GET  /api/health");
        System.out.println("POST /api/extract");
        System.out.println("POST /api/extract-file");
        System.out.println("POST /api/analyze");
        System.out.println("POST /api/analyze-file");
        System.out.println("==============================================");
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            sendResponse(exchange, 200, "");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, errorJson("Method not allowed."));
            return;
        }

        String response = "{"
                + "\"status\":\"online\","
                + "\"service\":\"GeneScan\","
                + "\"algorithm\":\"Boyer-Moore\""
                + "}";

        sendResponse(exchange, 200, response);
    }

    private static void handleAnalyze(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            sendResponse(exchange, 200, "");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, errorJson("Use POST for analysis."));
            return;
        }

        try {
            String requestBody = readRequestBody(exchange);
            String sequence = extractJsonValue(requestBody, "sequence");
            String motif = extractJsonValue(requestBody, "motif");
            String algorithm = extractJsonValue(requestBody, "algorithm");
                String inputType = extractJsonValue(requestBody, "inputType");
                String fileName = extractJsonValue(requestBody, "fileName");

                String result = analyzeSequence(sequence, motif, algorithm,
                    inputType == null ? "DIRECT" : inputType, fileName);
            sendResponse(exchange, 200, result);

        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, errorJson(e.getMessage()));
        } catch (Exception e) {
            sendResponse(exchange, 500, errorJson(e.getMessage()));
        }
    }

    private static void handleExtractText(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            sendResponse(exchange, 200, "");
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, errorJson("Use POST for DNA extraction."));
            return;
        }

        try {
            String requestBody = readRequestBody(exchange);
            String sourceText = extractJsonValue(requestBody, "text");
            sendResponse(exchange, 200, extractSequenceJson(sourceText, "DIRECT", null));
        } catch (IllegalArgumentException exception) {
            sendResponse(exchange, 400, errorJson(exception.getMessage()));
        } catch (Exception exception) {
            sendResponse(exchange, 500, errorJson(exception.getMessage()));
        }
    }

    private static void handleExtractFile(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            sendResponse(exchange, 200, "");
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, errorJson("Use POST for file extraction."));
            return;
        }

        try {
            MultipartUpload upload = parseMultipart(exchange);
            String fileName = upload.getFileName();
            byte[] fileBytes = upload.getFileBytes();
            if (fileName == null || fileName.trim().isEmpty()) {
                throw new IllegalArgumentException("A file is required for DNA extraction.");
            }
            if (fileBytes == null || fileBytes.length == 0) {
                throw new IllegalArgumentException("Uploaded file is empty.");
            }

            String inputType = FileDNAExtractor.detectInputType(fileName, fileBytes);
            String fileText = FileDNAExtractor.extractText(fileName, fileBytes, inputType);
            sendResponse(exchange, 200, extractSequenceJson(fileText, inputType, fileName));
        } catch (IllegalArgumentException exception) {
            sendResponse(exchange, 400, errorJson(exception.getMessage()));
        } catch (Exception exception) {
            sendResponse(exchange, 500, errorJson(exception.getMessage()));
        }
    }

    private static String extractSequenceJson(String sourceText, String inputType, String fileName) {
        String sequence = DNAParser.extractBestSequence(sourceText);
        String sequenceError = DNAParser.validateSequence(sequence);
        if (sequenceError != null) {
            throw new IllegalArgumentException("No meaningful DNA sequence was found. " + sequenceError);
        }
        if (sequence.length() > MAX_SEQUENCE_LENGTH) {
            throw new IllegalArgumentException("DNA sequence exceeds the 5,000,000-base limit.");
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"sequence\":\"").append(escapeJson(sequence)).append("\",");
        json.append("\"sequenceLength\":").append(sequence.length()).append(",");
        json.append("\"inputType\":\"").append(escapeJson(inputType)).append("\"");
        if (fileName != null) {
            json.append(",\"fileName\":\"").append(escapeJson(fileName)).append("\"");
        }
        return json.append("}").toString();
    }

    private static void handleAnalyzeFile(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            sendResponse(exchange, 200, "");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, errorJson("Use POST for file analysis."));
            return;
        }

        try {
            MultipartUpload upload = parseMultipart(exchange);
            String motif = upload.getField("motif");
            String algorithm = upload.getField("algorithm");
            String fileName = upload.getFileName();
            byte[] fileBytes = upload.getFileBytes();

            if (fileName == null || fileName.trim().isEmpty()) {
                throw new IllegalArgumentException("A file is required for analysis.");
            }

            if (motif == null || motif.trim().isEmpty()) {
                throw new IllegalArgumentException("DNA motif is required.");
            }

            if (fileBytes == null || fileBytes.length == 0) {
                throw new IllegalArgumentException("Uploaded file is empty.");
            }

            String inputType = FileDNAExtractor.detectInputType(fileName, fileBytes);
            String fileText = FileDNAExtractor.extractText(fileName, fileBytes, inputType);
            String extractedSequence = DNAParser.extractBestSequence(fileText);

            if (extractedSequence == null || extractedSequence.trim().isEmpty()) {
                throw new IllegalArgumentException("No DNA sequence could be extracted from the uploaded file.");
            }

            String result = analyzeSequence(extractedSequence, motif, algorithm, inputType, fileName);
            sendResponse(exchange, 200, result);

        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, errorJson(e.getMessage()));
        } catch (Exception e) {
            sendResponse(exchange, 500, errorJson(e.getMessage()));
        }
    }

    private static String analyzeSequence(String rawSequence, String rawMotif, String rawAlgorithm, String inputType, String fileName) {
        if (rawSequence == null || rawSequence.length() > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("DNA input is empty or exceeds the 10 MB limit.");
        }

        String sequence = DNAParser.extractBestSequence(rawSequence);
        String motif = DNAParser.normalizeMotif(rawMotif);
        String algorithm = normalizeAlgorithm(rawAlgorithm);

        String sequenceError = DNAParser.validateSequence(sequence);
        if (sequenceError != null) {
            throw new IllegalArgumentException(sequenceError);
        }

        String motifError = DNAParser.validateMotif(motif);
        if (motifError != null) {
            throw new IllegalArgumentException(motifError);
        }

        if (motif.length() > sequence.length()) {
            throw new IllegalArgumentException("DNA motif cannot be longer than the DNA sequence.");
        }

        if (sequence.length() > MAX_SEQUENCE_LENGTH) {
            throw new IllegalArgumentException("DNA sequence exceeds the 5,000,000-base limit.");
        }

        long matchCount;
        long comparisons;
        long executionTimeNanoseconds;
        ArrayList<Integer> matchPositions;
        long shifts = 0;
        BoyerMoore boyerMoore = null;

        if ("naive".equals(algorithm)) {
            NaiveSearch.SearchResult result = new NaiveSearch().search(sequence, motif);
            matchCount = result.getMatchCount();
            comparisons = result.getComparisons();
            executionTimeNanoseconds = result.getExecutionTimeNanoseconds();
            shifts = result.getShifts();
            matchPositions = new ArrayList<>(result.getMatchPositions());
        } else {
            boyerMoore = new BoyerMoore(motif);
            BoyerMoore.SearchResult result = boyerMoore.search(sequence);
            matchCount = result.getMatchCount();
            comparisons = result.getComparisons();
            executionTimeNanoseconds = result.getExecutionTimeNanoseconds();
            shifts = result.getShifts();
            matchPositions = new ArrayList<>(result.getMatchPositions());
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"algorithm\":\"").append(algorithm).append("\",");
        json.append("\"sequence\":\"").append(escapeJson(sequence)).append("\",");
        json.append("\"sequenceLength\":").append(sequence.length()).append(",");
        json.append("\"motif\":\"").append(escapeJson(motif)).append("\",");
        json.append("\"inputType\":\"").append(escapeJson(inputType)).append("\",");
        if (fileName != null && !fileName.isEmpty()) {
            json.append("\"fileName\":\"").append(escapeJson(fileName)).append("\",");
        }
        json.append("\"extractedSequence\":\"").append(escapeJson(sequence)).append("\",");
        json.append("\"matchCount\":").append(matchCount).append(",");
        json.append("\"matchPositions\":");
        appendIntegerList(json, matchPositions);
        json.append(",\"comparisons\":").append(comparisons).append(",");
        if ("boyerMoore".equals(algorithm)) {
            json.append("\"shifts\":").append(shifts).append(",");
        }
        json.append("\"executionTimeNanoseconds\":").append(executionTimeNanoseconds).append(",");
        json.append("\"executionTimeMilliseconds\":")
                .append(String.format(Locale.US, "%.6f", executionTimeNanoseconds / 1_000_000.0));

        if (boyerMoore != null) {
            int[] badCharacterTable = boyerMoore.getBadCharacterTable();
            json.append(",\"badCharacterTable\":{")
                    .append("\"A\":").append(badCharacterTable['A']).append(",")
                    .append("\"C\":").append(badCharacterTable['C']).append(",")
                    .append("\"G\":").append(badCharacterTable['G']).append(",")
                    .append("\"T\":").append(badCharacterTable['T']).append("},");
            json.append("\"goodSuffixTable\":");
            appendIntegerList(json, boyerMoore.getGoodSuffixTable());
        }

        json.append("}");
        return json.toString();
    }

    private static void handleHighlightFile(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            sendResponse(exchange, 200, "");
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, errorJson("Use POST to download a highlighted file."));
            return;
        }

        try {
            MultipartUpload upload = parseMultipart(exchange);
            if (upload.getFileName() == null || upload.getFileBytes() == null || upload.getFileBytes().length == 0) {
                throw new IllegalArgumentException("Upload a file before downloading the highlighted copy.");
            }

            String motif = DNAParser.normalizeMotif(upload.getField("motif"));
            String motifError = DNAParser.validateMotif(motif);
            if (motifError != null) {
                throw new IllegalArgumentException(motifError);
            }

            String inputType = FileDNAExtractor.detectInputType(upload.getFileName(), upload.getFileBytes());
            String sourceText = FileDNAExtractor.extractText(upload.getFileName(), upload.getFileBytes(), inputType);
            String html = createHighlightedHtml(upload.getFileName(), sourceText, motif);
            sendHighlightedFile(exchange, upload.getFileName(), html);
        } catch (IllegalArgumentException exception) {
            sendResponse(exchange, 400, errorJson(exception.getMessage()));
        } catch (Exception exception) {
            sendResponse(exchange, 500, errorJson(exception.getMessage()));
        }
    }

    private static String createHighlightedHtml(String fileName, String sourceText, String motif) {
        String sequence = DNAParser.extractBestSequence(sourceText);
        StringBuilder sourceBases = new StringBuilder();
        ArrayList<Integer> sourceOffsets = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= sourceText.length()) {
            int lineEnd = sourceText.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = sourceText.length();
            }
            int contentEnd = lineEnd > lineStart && sourceText.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            if (!sourceText.substring(lineStart, contentEnd).trim().startsWith(">")) {
                for (int offset = lineStart; offset < contentEnd; offset++) {
                    char base = Character.toUpperCase(sourceText.charAt(offset));
                    if (DNAParser.isDNACharacter(base)) {
                        sourceBases.append(base);
                        sourceOffsets.add(offset);
                    }
                }
            }
            if (lineEnd == sourceText.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }

        boolean[] highlighted = new boolean[sourceText.length()];
        int matchCount = 0;
        int sequenceOffset = sourceBases.indexOf(sequence);
        if (!sequence.isEmpty() && sequenceOffset >= 0) {
            for (int position = 0; position <= sequence.length() - motif.length(); position++) {
                if (sequence.regionMatches(position, motif, 0, motif.length())) {
                    int sourceStart = sourceOffsets.get(sequenceOffset + position);
                    int sourceEnd = sourceOffsets.get(sequenceOffset + position + motif.length() - 1);
                    for (int offset = sourceStart; offset <= sourceEnd; offset++) {
                        highlighted[offset] = true;
                    }
                    matchCount++;
                }
            }
        }

        StringBuilder html = new StringBuilder(sourceText.length() + 512);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Highlighted DNA - ").append(escapeHtml(fileName)).append("</title>")
                .append("<style>body{margin:32px;background:#07140e;color:#d9eee3;font:15px/1.7 sans-serif}h1{font-size:22px}p{color:#9db5a8}pre{padding:20px;background:#0b1e15;border:1px solid #214533;border-radius:8px;white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.8 monospace}mark{background:#35df86;color:#062013;border-radius:2px}</style>")
                .append("</head><body><h1>").append(escapeHtml(fileName)).append("</h1><p>Pattern: <strong>")
                .append(escapeHtml(motif)).append("</strong> · ").append(matchCount)
                .append(matchCount == 1 ? " match" : " matches").append(" highlighted</p><pre>");

        boolean inHighlight = false;
        for (int offset = 0; offset < sourceText.length(); offset++) {
            if (highlighted[offset] != inHighlight) {
                html.append(highlighted[offset] ? "<mark>" : "</mark>");
                inHighlight = highlighted[offset];
            }
            html.append(escapeHtmlCharacter(sourceText.charAt(offset)));
        }
        if (inHighlight) {
            html.append("</mark>");
        }
        return html.append("</pre></body></html>").toString();
    }

    private static String escapeHtml(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            escaped.append(escapeHtmlCharacter(value.charAt(index)));
        }
        return escaped.toString();
    }

    private static String escapeHtmlCharacter(char character) {
        switch (character) {
            case '&': return "&amp;";
            case '<': return "&lt;";
            case '>': return "&gt;";
            case '"': return "&quot;";
            case '\'': return "&#39;";
            default: return String.valueOf(character);
        }
    }

    private static void sendHighlightedFile(HttpExchange exchange, String fileName, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        int extensionIndex = safeName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? safeName.substring(0, extensionIndex) : safeName;
        if (baseName.isEmpty()) {
            baseName = "genescan";
        }

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + baseName + "-highlighted.html\"");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String normalizeAlgorithm(String algorithm) {
        if (algorithm == null || algorithm.trim().isEmpty()) {
            return "boyerMoore";
        }
        algorithm = algorithm.trim();
        if (algorithm.equalsIgnoreCase("boyerMoore")
                || algorithm.equalsIgnoreCase("boyer-moore")) {
            return "boyerMoore";
        }
        if (algorithm.equalsIgnoreCase("naive")
                || algorithm.equalsIgnoreCase("naiveSearch")) {
            return "naive";
        }
        throw new IllegalArgumentException("Choose either Boyer-Moore or Naive String Matching.");
    }

    private static void appendIntegerList(StringBuilder json, Iterable<Integer> values) {
        json.append("[");
        boolean first = true;
        for (Integer value : values) {
            if (!first) {
                json.append(",");
            }
            json.append(value);
            first = false;
        }
        json.append("]");
    }

    private static void appendIntegerList(StringBuilder json, int[] values) {
        json.append("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(values[i]);
        }
        json.append("]");
    }

    private static String errorJson(String message) {
        return "{\"error\":\"" + escapeJson(message) + "\"}";
    }

    private static MultipartUpload parseMultipart(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data")) {
            throw new IllegalArgumentException("Unsupported content type. Expected multipart/form-data.");
        }

        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length()).trim();
        if (boundary.startsWith("\"")) {
            boundary = boundary.substring(1);
        }
        if (boundary.endsWith("\"")) {
            boundary = boundary.substring(0, boundary.length() - 1);
        }

        byte[] bodyBytes = readRequestBytes(exchange);
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        Map<String, String> fields = new HashMap<>();
        String fileName = null;
        byte[] fileBytes = null;

        int searchStart = 0;
        while (searchStart < bodyBytes.length) {
            int boundaryIndex = indexOf(bodyBytes, boundaryBytes, searchStart);
            if (boundaryIndex < 0) {
                break;
            }

            int partStart = boundaryIndex + boundaryBytes.length;
            while (partStart < bodyBytes.length && (bodyBytes[partStart] == '\r' || bodyBytes[partStart] == '\n')) {
                partStart++;
            }

            int headerEnd = indexOf(bodyBytes, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), partStart);
            if (headerEnd < 0) {
                headerEnd = indexOf(bodyBytes, "\n\n".getBytes(StandardCharsets.UTF_8), partStart);
            }
            if (headerEnd < 0) {
                break;
            }

            int payloadStart = headerEnd + (bodyBytes[headerEnd] == '\r' ? 4 : 2);
            int nextBoundary = indexOf(bodyBytes, boundaryBytes, payloadStart);
            if (nextBoundary < 0) {
                nextBoundary = bodyBytes.length;
            }

            int payloadEnd = nextBoundary;
            while (payloadEnd > payloadStart && (bodyBytes[payloadEnd - 1] == '\n' || bodyBytes[payloadEnd - 1] == '\r')) {
                payloadEnd--;
            }

            byte[] headerBytes = copyBytes(bodyBytes, partStart, headerEnd);
            byte[] payload = copyBytes(bodyBytes, payloadStart, payloadEnd);
            String headerText = new String(headerBytes, StandardCharsets.UTF_8);

            String name = extractFormValue(headerText, "name");
            String uploadedFileName = extractFormValue(headerText, "filename");

            if (uploadedFileName != null && !uploadedFileName.trim().isEmpty()) {
                fileName = uploadedFileName;
                fileBytes = payload;
            } else if (name != null) {
                fields.put(name, new String(payload, StandardCharsets.UTF_8));
            }

            searchStart = nextBoundary;
        }

        return new MultipartUpload(fileName, fileBytes, fields);
    }

    private static int indexOf(byte[] source, byte[] target, int startIndex) {
        if (target.length == 0) {
            return startIndex;
        }

        for (int i = startIndex; i <= source.length - target.length; i++) {
            boolean matches = true;

            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                return i;
            }
        }

        return -1;
    }

    private static byte[] copyBytes(byte[] source, int start, int end) {
        byte[] result = new byte[end - start];
        System.arraycopy(source, start, result, 0, result.length);
        return result;
    }

    private static String extractFormValue(String headers, String key) {
        int index = headers.indexOf(key + "=");
        if (index < 0) {
            return null;
        }

        int quoteStart = headers.indexOf('"', index + key.length() + 1);
        if (quoteStart < 0) {
            return null;
        }

        int quoteEnd = headers.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            return null;
        }

        return headers.substring(quoteStart + 1, quoteEnd);
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPosition = json.indexOf(searchKey);

        if (keyPosition < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyPosition);
        if (colonIndex < 0) {
            return null;
        }

        int startIndex = json.indexOf('"', colonIndex + 1);
        if (startIndex < 0) {
            return null;
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;

        for (int i = startIndex + 1; i < json.length(); i++) {
            char character = json.charAt(i);

            if (escaped) {
                switch (character) {
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException exception) {
                                value.append('u');
                            }
                        } else {
                            value.append('u');
                        }
                        break;
                    default: value.append(character);
                }
                escaped = false;
                continue;
            }

            if (character == '\\') {
                escaped = true;
                continue;
            }

            if (character == '"') {
                break;
            }

            value.append(character);
        }

        return value.toString();
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(readRequestBytes(exchange), StandardCharsets.UTF_8);
    }

    private static byte[] readRequestBytes(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request exceeds the 10 MB file upload limit.");
        }
        return bytes;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static class MultipartUpload {
        private final String fileName;
        private final byte[] fileBytes;
        private final Map<String, String> fields;

        public MultipartUpload(String fileName, byte[] fileBytes, Map<String, String> fields) {
            this.fileName = fileName;
            this.fileBytes = fileBytes;
            this.fields = fields;
        }

        public String getFileName() {
            return fileName;
        }

        public byte[] getFileBytes() {
            return fileBytes;
        }

        public String getField(String name) {
            return fields.get(name);
        }
    }
}
