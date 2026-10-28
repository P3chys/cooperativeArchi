package io.github.p3chys.cooperativearchi.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/** The few GitHub REST calls cooperativeArchi needs, authenticated with a personal access token. */
public final class GitHubClient {

    public record User(String login, String name, String email, long id) {

        /** Name for history entries. */
        public String displayName() {
            return name == null || name.isBlank() ? login : name;
        }

        /** Public e-mail, or GitHub's no-reply address that still links commits to the account. */
        public String commitEmail() {
            return email != null && !email.isBlank() ? email : id + "+" + login + "@users.noreply.github.com";
        }
    }

    public record Repo(String fullName, String cloneUrl, boolean isPrivate) {
    }

    private static final String API = "https://api.github.com";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String token;

    public GitHubClient(String token) {
        this.token = token == null ? "" : token.trim();
    }

    /** Git credentials for https://github.com URLs. */
    public static CredentialsProvider credentials(String token) {
        return new UsernamePasswordCredentialsProvider("x-access-token", token == null ? "" : token.trim());
    }

    public User user() throws IOException {
        String body = call("GET", "/user", null, 200);
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        return new User(string(body, "login"), string(body, "name"), string(body, "email"),
                id.find() ? Long.parseLong(id.group(1)) : 0);
    }

    /** Creates an empty repository for the authenticated user, or for an organization if {@code owner} is given. */
    public Repo createRepo(String owner, String name, boolean isPrivate, String description) throws IOException {
        String json = "{\"name\":" + quote(name) + ",\"private\":" + isPrivate + ",\"description\":" + quote(description)
                + ",\"auto_init\":false}";
        String path = owner == null || owner.isBlank() ? "/user/repos" : "/orgs/" + owner + "/repos";
        String body = call("POST", path, json, 201);
        return new Repo(string(body, "full_name"), string(body, "clone_url"), body.contains("\"private\":true"));
    }

    private String call(String method, String path, String json, int expected) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token);
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (r.statusCode() == 401) {
            throw new IOException("GitHub rejected the access token (expired or wrong?)");
        }
        if (r.statusCode() != expected) {
            String msg = string(r.body(), "message");
            throw new IOException("GitHub error " + r.statusCode() + ": " + (msg == null ? r.body() : msg)
                    + (r.body().contains("name already exists") ? " (a repository with this name already exists)" : ""));
        }
        return r.body();
    }

    /** Top-level string field of a JSON object (null if absent or null). Good enough for GitHub's flat replies. */
    static String string(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(null|\"((?:[^\"\\\\]|\\\\.)*)\")").matcher(json);
        if (!m.find() || m.group(2) == null) {
            return null;
        }
        return m.group(2).replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\");
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }
}
