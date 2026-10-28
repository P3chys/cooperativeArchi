package io.github.p3chys.cooperativearchi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import io.github.p3chys.cooperativearchi.model.ModelGraph;

/** Test model fixtures, edited as text so each test reads like the change an analyst made. */
public final class TestModels {

    private TestModels() {
    }

    public static String text() {
        try (InputStream in = TestModels.class.getResourceAsStream("/test-model.archimate")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ModelGraph graph(String xml) {
        try {
            return ModelGraph.read(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ModelGraph base() {
        return graph(text());
    }

    /** Applies literal replacements pairwise: edit(text, "a", "b", "c", "d"). */
    public static String edit(String xml, String... pairs) {
        String out = xml;
        for (int i = 0; i < pairs.length; i += 2) {
            if (!out.contains(pairs[i])) {
                throw new IllegalArgumentException("Fixture does not contain: " + pairs[i]);
            }
            out = out.replace(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    public static ModelGraph edited(String... pairs) {
        return graph(edit(text(), pairs));
    }
}
