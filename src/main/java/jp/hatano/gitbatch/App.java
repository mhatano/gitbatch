package jp.hatano.gitbatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Hello world!
 *
 */
public class App {

    private static String lastSelectedLocalBranch = null;
    private static String lastSelectedMergeBranch = null;

    /**
     * Represents a node in the branch tree structure.
     */
    private static class BranchNode {
        // Using LinkedHashMap to maintain insertion order for predictable display
        Map<String, BranchNode> children = new LinkedHashMap<>();
        String fullName; // Stores the full branch name if this node is a leaf

        void insert(String path) {
            this.fullName = path;
        }
    }

    public static void main(String[] args) {
        loadPreferences();
        try (Scanner scanner = new Scanner(System.in)) {
            // 1. Select target local branch
            List<String> localBranches = getGitBranches(false);
            if (localBranches.isEmpty()) {
                System.out.println("No local branches found. Make sure you are in a git repository.");
                return;
            }
            String localBranch = selectBranch(scanner, "local", localBranches, lastSelectedLocalBranch);

            // 2. Select another branch from local or remote
            List<String> allBranches = new ArrayList<>(localBranches);
            List<String> remoteBranches = getGitBranches(true); // Get remote branches
            allBranches.addAll(remoteBranches); // Add them to the list

             if (allBranches.size() <= 1) { // Check if there are other branches to merge
                System.out.println("No other branches available to merge.");
                return;
            }
            String sourceBranch = selectBranch(scanner, "source (to merge from)", allBranches, lastSelectedMergeBranch);

            // 3. Determine the local name for the source branch
            String localSourceBranchName = sourceBranch;
            if (sourceBranch.startsWith("origin/")) { // Simple assumption for remote branches
                localSourceBranchName = sourceBranch.substring("origin/".length());
            }

            // 4. Checkout source branch and pull latest changes
            System.out.println("\n--- Checking out source branch: " + localSourceBranchName + " ---");
            executeCommand("git", "checkout", localSourceBranchName);

            System.out.println("\n--- Pulling latest changes for " + localSourceBranchName + " ---");
            executeCommand("git", "pull");

            // 5. Checkout target branch
            System.out.println("\n--- Checking out target branch: " + localBranch + " ---");
            executeCommand("git", "checkout", localBranch);

            // 6. Merge the updated source branch into the target branch
            System.out.println("\n--- Merging branch " + localSourceBranchName + " into " + localBranch + " ---");
            executeCommand("git", "merge", localSourceBranchName);

            System.out.println("\nBatch operation completed.");
            savePreferences(localBranch, sourceBranch);

        } catch (IOException | InterruptedException e) {
            System.err.println("An error occurred while executing git command.");
            e.printStackTrace();
        }
    }

    private static void loadPreferences() {
        Path configFile = Paths.get(System.getProperty("user.home"), ".gitbatch", "config.properties");
        if (Files.exists(configFile)) {
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(configFile)) {
                props.load(input);
                lastSelectedLocalBranch = props.getProperty("lastSelectedLocalBranch");
                lastSelectedMergeBranch = props.getProperty("lastSelectedMergeBranch");
                System.out.println("Preferences loaded.");
            } catch (IOException e) {
                System.err.println("Warning: Could not load preferences. " + e.getMessage());
            }
        }
    }

    private static void savePreferences(String localBranch, String mergeBranch) {
        Path configDir = Paths.get(System.getProperty("user.home"), ".gitbatch");
        Path configFile = configDir.resolve("config.properties");
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Properties props = new Properties();
            if (localBranch != null) {
                props.setProperty("lastSelectedLocalBranch", localBranch);
            }
            if (mergeBranch != null) {
                props.setProperty("lastSelectedMergeBranch", mergeBranch);
            }

            try (OutputStream output = Files.newOutputStream(configFile)) {
                props.store(output, "GitBatch Preferences");
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not save preferences. " + e.getMessage());
        }
    }

    private static String selectBranch(Scanner scanner, String type, List<String> branches, String lastSelected) {
        BranchNode root = buildBranchTree(branches);
        List<String> selectableBranches = new ArrayList<>();

        System.out.println("\nPlease select a " + type + " branch:");
        printBranchTree(root, "", selectableBranches);

        String defaultBranch = null;
        if (lastSelected != null && selectableBranches.contains(lastSelected)) {
            defaultBranch = lastSelected;
        }

        while (true) {
            System.out.print("Enter number (1-" + selectableBranches.size() + ")");
            if (defaultBranch != null) {
                System.out.print(" [default: " + defaultBranch + "]: ");
            } else {
                System.out.print(": ");
            }

            String input = scanner.nextLine();

            if (input.isEmpty() && defaultBranch != null) {
                System.out.println("Using default: " + defaultBranch);
                return defaultBranch;
            }

            try {
                int choice = Integer.parseInt(input);
                if (choice >= 1 && choice <= selectableBranches.size()) {
                    return selectableBranches.get(choice - 1);
                }
                System.out.println("Invalid selection. Please try again.");
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number or press Enter for default.");
            }
        }
    }

    private static BranchNode buildBranchTree(List<String> branches) {
        BranchNode root = new BranchNode();
        for (String branch : branches) {
            BranchNode currentNode = root;
            String[] parts = branch.split("/");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                currentNode = currentNode.children.computeIfAbsent(part, k -> new BranchNode());
                if (i == parts.length - 1) {
                    // This is a leaf node, store the full name
                    currentNode.insert(branch);
                }
            }
        }
        return root;
    }

    private static void printBranchTree(BranchNode node, String prefix, List<String> selectableBranches) {
        // Sort keys to ensure consistent order, especially for top-level branches
        List<String> sortedKeys = new ArrayList<>(node.children.keySet());

        for (int i = 0; i < sortedKeys.size(); i++) {
            String key = sortedKeys.get(i);
            BranchNode child = node.children.get(key);
            boolean isLast = (i == sortedKeys.size() - 1);
            String connector = isLast ? "└── " : "├── ";

            if (child.fullName != null && child.children.isEmpty()) { // It's a selectable branch (leaf)
                selectableBranches.add(child.fullName);
                System.out.printf("%s%s[%d] %s%n", prefix, connector, selectableBranches.size(), key);
            } else { // It's a folder
                System.out.println(prefix + connector + key + "/");
                printBranchTree(child, prefix + (isLast ? "    " : "│   "), selectableBranches);
            }
        }
    }

    private static List<String> getGitBranches(boolean remote) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("branch");
        if (remote) {
            command.add("-r");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        List<String> branches = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            branches = reader.lines()
                    .map(line -> line.trim().replace("* ", ""))
                    // Ignore the HEAD pointer in remotes
                    .filter(branchName -> !branchName.contains("->"))
                    .collect(Collectors.toList());
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            printStream(new BufferedReader(new InputStreamReader(process.getErrorStream())), "Error");
            throw new IOException("Git command failed with exit code " + exitCode);
        }

        return branches;
    }

    private static void executeCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        // Print standard output and standard error to the console
        printStream(new BufferedReader(new InputStreamReader(process.getInputStream())), "Output");
        printStream(new BufferedReader(new InputStreamReader(process.getErrorStream())), "Error");

        int exitCode = process.waitFor();
        System.out.println("Command '" + String.join(" ", command) + "' finished with exit code " + exitCode);
    }

    private static void printStream(BufferedReader reader, String type) throws IOException {
        // Using forEach to print each line from the stream.
        // This is a more modern and concise way to handle streams of lines.
        reader.lines().forEach(System.out::println);
    }
}
