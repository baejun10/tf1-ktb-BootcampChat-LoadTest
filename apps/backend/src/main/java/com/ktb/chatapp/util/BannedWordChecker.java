package com.ktb.chatapp.util;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Trie trie;

    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalizedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");

        this.trie = Trie.builder()
                .onlyWholeWords()
                .addKeywords(normalizedWords)
                .build();
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        Collection<Emit> emits = trie.parseText(normalizedMessage);
        return !emits.isEmpty();
    }
}
