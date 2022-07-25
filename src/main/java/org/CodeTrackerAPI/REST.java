package org.CodeTrackerAPI;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Undertow;
import io.undertow.Handlers;
import io.undertow.util.Headers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.HttpString;

import org.refactoringminer.util.GitServiceImpl;
import org.refactoringminer.api.GitService;

import org.codetracker.api.*;
import org.codetracker.element.Method;
import org.codetracker.change.Change;

import org.eclipse.jgit.lib.Repository;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.GHCommit.File;

public class REST {
    public static void main(String[] args) {
        PathHandler path = Handlers.path()
                .addPrefixPath("/api", Handlers.routing()
                        .get("/method", exchange -> {
                            Map<String, Deque<String>> params = exchange.getQueryParameters();
                            String owner = params.get("owner").getFirst();
                            String repoName = params.get("repoName").getFirst();
                            String filePath = params.get("filePath").getFirst();
                            String commitId = params.get("commitId").getFirst();
                            String methodName = params.get("methodName").getFirst();

                            Integer lineNumber = Integer.parseInt(params.get("lineNumber").getFirst());
                            exchange.getResponseHeaders()
                                    .put(new HttpString("Access-Control-Allow-Origin"), "*")
                                    .put(Headers.CONTENT_TYPE, "text/plain");
                            String response = CTMethod(owner, repoName, filePath, commitId, methodName, lineNumber);
                            exchange.getResponseSender()
                                    .send(response);
                        }));

        Undertow server = Undertow.builder()
                .addHttpListener(5000, "0.0.0.0")
                .setHandler(path).build();

        server.start();
    }

    private static String CTMethod(String owner, String repoName, String filePath, String commitId, String methodName,
            Integer lineNumber) {
        GitService gitService = new GitServiceImpl();
        ArrayList<CTHMElement> changeLog = new ArrayList<>();
        CompletableFuture<List<File>> currentFiles;

        try (Repository repository = gitService.cloneIfNotExists("tmp/" + repoName,
                "https://github.com/" + owner + "/" + repoName + ".git")) {

            MethodTracker methodTracker = CodeTracker.methodTracker()
                    .repository(repository)
                    .filePath(filePath)
                    .startCommitId(commitId)
                    .methodName(methodName)
                    .methodDeclarationLineNumber(lineNumber)
                    .build();

            History<Method> methodHistory = methodTracker.track();

            for (History.HistoryInfo<Method> historyInfo : methodHistory.getHistoryInfoList()) {
                ArrayList<String> currentChanges = new ArrayList<>();
                System.out.println("======================================================");
                System.out.println("Commit ID: " + historyInfo.getCommitId());
                System.out.println("Date: " +
                        LocalDateTime.ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC));
                System.out.println("Before: " + historyInfo.getElementBefore().getName());
                System.out.println("After: " + historyInfo.getElementAfter().getName());

                for (Change change : historyInfo.getChangeList()) {
                    System.out.println(change.getType().getTitle() + ": " + change);
                    currentChanges.add(change.getType().getTitle() + ": " + change);
                }
                // Uncomment for git commit files in API response
                // 
                // currentFiles = CompletableFuture
                //         .supplyAsync(() -> getCommitFiles(owner, repoName, historyInfo.getCommitId()));
                // CTHMElement currentElement = new CTHMElement(historyInfo.getCommitId(),
                //         LocalDateTime.ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC),
                //         historyInfo.getElementBefore().getName(), historyInfo.getElementAfter().getName(),
                //         currentChanges, currentFiles.get());
                CTHMElement currentElement = new CTHMElement(historyInfo.getCommitId(),
                        LocalDateTime.ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC).toString(),
                        historyInfo.getElementBefore().getName(), historyInfo.getElementAfter().getName(),
                        currentChanges);
                changeLog.add(currentElement);
            }
            System.out.println("======================================================");
            ObjectMapper mapper = new ObjectMapper();
            mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            try {
                String json = mapper.writeValueAsString(changeLog);
                // System.out.println("JSON = " + json);
                return json;
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.out.println("Something went wrong: " + e);
        }

        return "";
    }

    public static List<File> getCommitFiles(String owner, String repoName, String commitId) {
        try {
            GitHub gitHub = GitHubBuilder.fromEnvironment().build();
            GHCommit commit = gitHub.getRepository(owner + "/" + repoName).getCommit(commitId);
            List<File> files = commit.getFiles();
            return files;
        } catch (Exception e) {
            System.out.println("An error has occured: " + e);
            return null;
        }
    }

    // CodeTracker History Method Element
    private static class CTHMElement {
        // for files add  List<File> files
        String commitId;
        String date;
        String before;
        String after;
        ArrayList<String> changes;

        private CTHMElement(String commitId, String date, String before, String after, ArrayList<String> changes) {
            this.commitId = commitId;
            this.date = date;
            this.before = before;
            this.after = after;
            this.changes = changes;
        }
    }
}
