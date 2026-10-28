package io.github.p3chys.cooperativearchi.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitHubClientTest {

    @ParameterizedTest
    @CsvSource({
            "https://github.com/owner/repo.git, true",
            "HTTPS://GitHub.com/owner/repo, true",
            "http://github.com/owner/repo.git, false",
            "https://github.com.evil.example/owner/repo.git, false",
            "https://evil.example/github.com/repo.git, false",
            "https://user@github.com/owner/repo.git, false",
            "https://github.com:8443/owner/repo.git, false",
            "file:///C:/repo.git, false" })
    void tokenIsOnlyForGitHub(String url, boolean expected) {
        assertThat(GitHubClient.isGitHubUrl(url)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({ "'{\"login\":\"a\\\"b\",\"name\":null}', login, a\"b" })
    void readsStringFields(String json, String field, String expected) {
        assertThat(GitHubClient.string(json, field)).isEqualTo(expected);
    }
}
