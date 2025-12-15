package com.ktb.chatapp.util;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final TrieNode root;

    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalizedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");

        this.root = buildTrie(normalizedWords);
    }

    private TrieNode buildTrie(Set<String> words) {
        TrieNode root = new TrieNode();

        for (String word : words) {
            TrieNode current = root;
            for (char c : word.toCharArray()) {
                current = current.children.computeIfAbsent(c, k -> new TrieNode());
            }
            current.isWordEnd = true;
        }

        buildFailureLinks(root);
        return root;
    }

    private void buildFailureLinks(TrieNode root) {
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

                TrieNode failNode = current.failure;
                while (failNode != root && !failNode.children.containsKey(c)) {
                    failNode = failNode.failure;
                }

                child.failure = failNode.children.getOrDefault(c, root);
                if (child.failure == child) {
                    child.failure = root;
                }

                queue.offer(child);
            }
        }
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        TrieNode current = root;

        for (int i = 0; i < normalizedMessage.length(); i++) {
            char c = normalizedMessage.charAt(i);

            if (!Character.isLetterOrDigit(c)) {
                current = root;
                continue;
            }

            while (current != root && !current.children.containsKey(c)) {
                current = current.failure;
            }

            current = current.children.getOrDefault(c, root);

            TrieNode temp = current;
            while (temp != root) {
                if (temp.isWordEnd) {
                    return true;
                }
                temp = temp.failure;
            }
        }

        return false;
    }

    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        TrieNode failure;
        boolean isWordEnd = false;
    }
}
