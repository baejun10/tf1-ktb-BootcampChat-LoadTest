package com.ktb.chatapp.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Set<String> bannedWords;
    private final Pattern wordBoundaryPattern;

    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalizedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");

        this.bannedWords = normalizedWords;
        this.wordBoundaryPattern = Pattern.compile("\\W+");
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        String[] words = wordBoundaryPattern.split(normalizedMessage);

        for (String word : words) {
            if (!word.isEmpty() && bannedWords.contains(word)) {
                return true;
            }
        }

        return false;
    }
}
