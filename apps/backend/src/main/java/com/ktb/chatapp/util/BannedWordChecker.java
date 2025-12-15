package com.ktb.chatapp.util;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final AhoCorasick ahoCorasick;

    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalizedWords = bannedWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");
        this.ahoCorasick = new AhoCorasick(normalizedWords);
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        return ahoCorasick.search(normalizedMessage);
    }

    private static class AhoCorasick {
        private final TrieNode root;

        AhoCorasick(Set<String> patterns) {
            this.root = new TrieNode();
            for (String pattern : patterns) {
                if (pattern != null && !pattern.isEmpty()) {
                    insert(pattern);
                }
            }
            buildFailureLinks();
        }

        private void insert(String pattern) {
            TrieNode current = root;
            for (char c : pattern.toCharArray()) {
                current = current.children.computeIfAbsent(c, k -> new TrieNode());
            }
            current.isEndOfWord = true;
        }

        private void buildFailureLinks() {
            Queue<TrieNode> queue = new LinkedList<>();
            root.failure = root;

            for (TrieNode child : root.children.values()) {
                child.failure = root;
                queue.offer(child);
            }

            while (!queue.isEmpty()) {
                TrieNode current = queue.poll();

                for (Map.Entry<Character, TrieNode> entry : current.children.entrySet()) {
                    char c = entry.getKey();
                    TrieNode child = entry.getValue();
                    queue.offer(child);

                    TrieNode failure = current.failure;
                    while (failure != root && !failure.children.containsKey(c)) {
                        failure = failure.failure;
                    }

                    child.failure = failure.children.getOrDefault(c, root);
                }
            }
        }

        boolean search(String text) {
            TrieNode current = root;

            for (char c : text.toCharArray()) {
                while (current != root && !current.children.containsKey(c)) {
                    current = current.failure;
                }

                current = current.children.getOrDefault(c, root);

                TrieNode temp = current;
                while (temp != root) {
                    if (temp.isEndOfWord) {
                        return true;
                    }
                    temp = temp.failure;
                }
            }

            return false;
        }

        private static class TrieNode {
            final Map<Character, TrieNode> children = new HashMap<>();
            volatile TrieNode failure;
            volatile boolean isEndOfWord;
        }
    }
}
