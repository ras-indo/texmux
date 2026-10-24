package texmux.shared.termux.data;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TermuxUrlUtils {

    public static Pattern URL_MATCH_REGEX;
    public static Pattern PLAIN_URL_MATCH_REGEX;

    /** Regex for URLs with explicit scheme (e.g., https://example.com) */
    public static Pattern getUrlMatchRegex() {
        if (URL_MATCH_REGEX != null) return URL_MATCH_REGEX;

        StringBuilder regex_sb = new StringBuilder();

        regex_sb.append("(");                       // Begin first matching group.
        regex_sb.append("(?:");                     // Begin scheme group.
        regex_sb.append("dav|");                    // The DAV proto.
        regex_sb.append("dict|");                   // The DICT proto.
        regex_sb.append("dns|");                    // The DNS proto.
        regex_sb.append("file|");                   // File path.
        regex_sb.append("finger|");                 // The Finger proto.
        regex_sb.append("ftp(?:s?)|");              // The FTP proto.
        regex_sb.append("git|");                    // The Git proto.
        regex_sb.append("gemini|");                 // The Gemini proto.
        regex_sb.append("gopher|");                 // The Gopher proto.
        regex_sb.append("http(?:s?)|");             // The HTTP proto.
        regex_sb.append("imap(?:s?)|");             // The IMAP proto.
        regex_sb.append("irc(?:[6s]?)|");           // The IRC proto.
        regex_sb.append("ip[fn]s|");                // The IPFS proto.
        regex_sb.append("ldap(?:s?)|");             // The LDAP proto.
        regex_sb.append("mailto|");                 // The Mailto proto.
        regex_sb.append("pop3(?:s?)|");             // The POP3 proto.
        regex_sb.append("redis(?:s?)|");            // The Redis proto.
        regex_sb.append("rsync|");                  // The Rsync proto.
        regex_sb.append("rtsp(?:[su]?)|");          // The RTSP proto.
        regex_sb.append("sftp|");                   // The SFTP proto.
        regex_sb.append("smb(?:s?)|");              // The SAMBA proto.
        regex_sb.append("smtp(?:s?)|");             // The SMTP proto.
        regex_sb.append("svn(?:(?:\\+ssh)?)|");     // The Subversion proto.
        regex_sb.append("tcp|");                    // The TCP proto.
        regex_sb.append("telnet|");                 // The Telnet proto.
        regex_sb.append("tftp|");                   // The TFTP proto.
        regex_sb.append("udp|");                    // The UDP proto.
        regex_sb.append("vnc|");                    // The VNC proto.
        regex_sb.append("ws(?:s?)");                // The Websocket proto.
        regex_sb.append(")://");                    // End scheme group.
        regex_sb.append(")");                       // End first matching group.


        // Begin second matching group.
        regex_sb.append("(");

        // User name and/or password in format 'user:pass@'.
        regex_sb.append("(?:\\S+(?::\\S*)?@)?");

        // Begin host group.
        regex_sb.append("(?:");

        // IP address (from http://www.regular-expressions.info/examples.html).
        regex_sb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|");

        // Host name or domain.
        regex_sb.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*){1,}[a-z\\u00a1-\\uffff0-9]{1,}))?|");

        // Just path. Used in case of 'file://' scheme.
        regex_sb.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)");

        // End host group.
        regex_sb.append(")");

        // Port number.
        regex_sb.append("(?::\\d{1,5})?");

        // Resource path with optional query string.
        regex_sb.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // Fragment.
        regex_sb.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // End second matching group.
        regex_sb.append(")");

        URL_MATCH_REGEX = Pattern.compile(
            regex_sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        return URL_MATCH_REGEX;
    }

    /**
     * Combined regex that matches:
     * 1. URLs with explicit scheme (group 1, existing behavior)
     * 2. Plain domain names without scheme (group 2)
     * 3. Email addresses (group 3)
     */
    public static Pattern getPlainUrlMatchRegex() {
        if (PLAIN_URL_MATCH_REGEX != null) return PLAIN_URL_MATCH_REGEX;

        StringBuilder sb = new StringBuilder();

        // Alternative 1: URL with scheme (existing, captures full URL including scheme)
        sb.append(getUrlMatchRegex().pattern());
        sb.append("|");

        // Alternative 2: Plain domain name without scheme → wrap as https://
        // Negative lookbehind avoids matching domains inside existing scheme:// URLs
        sb.append("(?<![\\.:/\\w])");
        sb.append("(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+");
        sb.append("(?:[a-zA-Z]{2,})");
        // Optional port
        sb.append("(?::\\d{1,5})?");
        // Optional path
        sb.append("(?:/[\\w\\-.~!$&'()*+,;=:@%/?]*)?");
        sb.append("|");

        // Alternative 3: Email address → wrap as mailto:
        // \b boundaries ensure full-word match
        sb.append("\\b");
        sb.append("[\\w.~%+\\-]+@[\\w.-]+\\.(?:[a-zA-Z]{2,})");
        sb.append("\\b");

        PLAIN_URL_MATCH_REGEX = Pattern.compile(
            sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        return PLAIN_URL_MATCH_REGEX;
    }

    /**
     * Extract actionable URLs/emails from text.
     * Returns proper URIs (scheme:// URLs, mailto: emails) ready for Intent.ACTION_VIEW.
     */
    public static LinkedHashSet<CharSequence> extractUrls(String text) {
        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return urlSet;

        // Phase 1: Match URLs with scheme (existing behavior)
        Matcher urlMatcher = getUrlMatchRegex().matcher(text);
        while (urlMatcher.find()) {
            urlSet.add(text.substring(urlMatcher.start(1), urlMatcher.end()));
        }
        // Phase 2: Match plain domains and emails via combined regex
        Matcher plainMatcher = getPlainUrlMatchRegex().matcher(text);
        while (plainMatcher.find()) {
            String match = text.substring(plainMatcher.start(), plainMatcher.end());

            // Check if this match overlaps with a Phase 1 URL (already extracted)
            boolean overlaps = false;
            for (CharSequence existing : urlSet) {
                if (existing.toString().contains(match) || match.contains(existing.toString())) {
                    overlaps = true;
                    break;
                }
            }
            // Convert plain domain → https:// and email → mailto:
            String result = convertToActionableUri(match);
            if (result != null) {
                urlSet.add(result);
            }
        }

        // Phase 3: Absolute file paths (e.g., /sdcard/Download/file)
        // Must start with / and have at least 2 path segments.
        // Checked after Phase 2 so overlapping matches are automatically handled.
        if (text.startsWith("/") && !text.contains("://")) {
            String pathResult = convertFileToActionableUri(text);
            if (pathResult != null) {
                // Avoid duplicating if Phase 1 already matched it as file://
                boolean overlaps = false;
                for (CharSequence existing : urlSet) {
                    if (existing.toString().contains(pathResult.replace("file://", "")) ||
                        pathResult.contains(existing.toString())) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) {
                    urlSet.add(pathResult);
                }
            }
        }

        return urlSet;
    }

    /**
     * Convert a raw text match to an actionable URI.
     * - Plain domains get https:// prefix
     * - Emails get mailto: prefix
     * - Already-valid URIs pass through
     */
    private static String convertToActionableUri(String match) {
        if (match == null || match.isEmpty()) return null;

        // Skip already matched by scheme regex
        if (match.contains("://")) return null;

        // Check if it's an email (contains @ with a valid domain)
        int atIndex = match.indexOf('@');
        if (atIndex > 0 && atIndex < match.length() - 1) {
            String domain = match.substring(atIndex + 1);
            if (domain.contains(".") && domain.length() > 3) {
                return "mailto:" + match;
            }
        }

        // Check if it's a plain domain (has a dot, ends with alpha TLD)
        if (match.contains(".")) {
            String[] parts = match.split("\\.");
            String lastPart = parts[parts.length - 1];
            if (lastPart.length() >= 2 && lastPart.matches("[a-zA-Z]+")) {
                // Skip if it looks like a file extension (common non-TLD patterns)
                String lowerMatch = match.toLowerCase();
                if (lowerMatch.endsWith(".txt") || lowerMatch.endsWith(".md") ||
                    lowerMatch.endsWith(".log") || lowerMatch.endsWith(".cfg") ||
                    lowerMatch.endsWith(".conf") || lowerMatch.endsWith(".json") ||
                    lowerMatch.endsWith(".xml") || lowerMatch.endsWith(".yml") ||
                    lowerMatch.endsWith(".yaml") || lowerMatch.endsWith(".ini") ||
                    lowerMatch.endsWith(".tmp") || lowerMatch.endsWith(".bak") ||
                    lowerMatch.endsWith(".exe") || lowerMatch.endsWith(".dll") ||
                    lowerMatch.endsWith(".so") || lowerMatch.endsWith(".apk") ||
                    lowerMatch.endsWith(".png") || lowerMatch.endsWith(".jpg") ||
                    lowerMatch.endsWith(".jpeg") || lowerMatch.endsWith(".gif") ||
                    lowerMatch.endsWith(".svg") || lowerMatch.endsWith(".ico") ||
                    lowerMatch.endsWith(".pdf") || lowerMatch.endsWith(".zip") ||
                    lowerMatch.endsWith(".tar") || lowerMatch.endsWith(".gz") ||
                    lowerMatch.endsWith(".bz2"))
                    return null;
                return "https://" + match;
            }
        }

        return null;
    }

    /**
     * Convert an absolute file path (/dir/file) to a file:// URI.
     * Requires at least 2 path segments (e.g. /sdcard/Download).
     */
    private static String convertFileToActionableUri(String match) {
        if (match == null || match.isEmpty() || !match.startsWith("/")) return null;
        // Must have at least 2 segments: /dir/file
        int secondSlash = match.indexOf('/', 1);
        if (secondSlash < 0 || secondSlash >= match.length() - 1) return null;
        // Skip tokens containing protocol or email noise
        if (match.contains("://") || match.contains("@")) return null;
        return "file://" + match;
    }
}
