package org.CodeTrackerAPI;

import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import gr.uom.java.xmi.LocationInfo;
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
import org.codetracker.element.Attribute;
import org.codetracker.element.Method;
import org.codetracker.element.Variable;
import org.codetracker.util.CodeElementLocator;
import org.codetracker.change.Change;
import org.codetracker.change.EvolutionHook;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.lib.Repository;

public class REST {
    public static void main(String[] args) {
        PathHandler path = Handlers.path()
                .addPrefixPath("/api", Handlers.routing()
                        .get("/method", exchange -> {

                            Map<String, Deque<String>> params = exchange.getQueryParameters();
                            String owner = params.get("owner").getFirst();
                            String repoName = params.get("repoName").getFirst();
                            String commitId = params.get("commitId").getFirst();
                            String filePath = params.get("filePath").getFirst();
                            String methodName = params.get("methodName").getFirst();
                            Integer lineNumber = Integer.parseInt(params.get("lineNumber").getFirst());
                            String response;

                            GitService gitService = new GitServiceImpl();

                            try (Repository repository = gitService.cloneIfNotExists("tmp/" + repoName,
                                    "https://github.com/" + owner + "/" + repoName + ".git")) {

                                try (Git git = new Git(repository)) {
                                    PullResult call = git.pull().call();
                                    System.out.println("Pulled from the remote repository: " + call);
                                }
                                CodeElementLocator locator = new CodeElementLocator(repository, commitId, filePath,
                                methodName, lineNumber);
                                CodeElement codeElement = locator.locate();
                                if (codeElement == null){
                                    throw new Exception("Selected code element is invalid.");
                                }
                                if (codeElement.getClass() == Method.class){
                                    response = CTMethod(owner, repository, filePath, commitId, methodName, lineNumber);
                                } else if (codeElement.getClass() == Variable.class){
                                    response = "";
                                    // response = CTVariable();
                                } else if (codeElement.getClass() == Attribute.class){
                                    // response = CTAttribute();
                                    response = "";
                                } else {
                                    response = "";
                                }
                                
                                exchange.getResponseHeaders()
                                        .put(new HttpString("Access-Control-Allow-Origin"), "*")
                                        .put(Headers.CONTENT_TYPE, "text/plain");
                                exchange.getResponseSender()
                                        .send(response);

                            } catch (Exception e) {
                                System.out.println("Something went wrong: " + e);
                            }
                        }));

        Undertow server = Undertow.builder()
                .addHttpListener(5000, "0.0.0.0")
                .setHandler(path).build();

        server.start();
    }

    private static String CTMethod(String owner, Repository repository, String filePath, String commitId, String methodName,
            Integer lineNumber) {
        ArrayList<CTHMElement> changeLog = new ArrayList<>();
        try {

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
                CodeElement evolutionHook = null;
                Boolean evolutionPresent = false;
                System.out.println("======================================================");
                System.out.println("Commit ID: " + historyInfo.getCommitId());
                System.out.println("Date: " +
                        LocalDateTime.ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC));
                System.out.println("Before: " + historyInfo.getElementBefore().getName());
                System.out.println("After: " + historyInfo.getElementAfter().getName());
                for (Change change : historyInfo.getChangeList()) {
                    System.out.println(change.getType().getTitle() + ": " + change);
                    currentChanges.add(change.getType().getTitle() + ": " + change);
                    evolutionPresent = change.getEvolutionHook().isPresent();
                    if(evolutionPresent) {
                        evolutionHook = change.getEvolutionHook().get().getElementAfter();
                    }
                }

                CTHMElement currentElement = new CTHMElement(historyInfo.getCommitId(),
                        LocalDateTime.ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC).toString(),
                        historyInfo.getElementBefore(), historyInfo.getElementAfter(),
                        historyInfo.getCommitterName(),
                        currentChanges, evolutionPresent, evolutionHook);
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
        } catch(Exception e){
            return e.toString();
        }

        return "";
    }

    // CodeTracker History Method Element
    private static class CTHMElement {
        String commitId;
        String date;
        String before;
        Integer beforeLine;
        String beforePath;
        String after;
        Integer afterLine;
        String afterPath;
        String committer;
        ArrayList<String> changes;
        String evolutionHook;
        Integer evolutionHookLine;
        String evolutionHookPath;

        private CTHMElement(String commitId, String date, CodeElement before, CodeElement after, String committer,
                ArrayList<String> changes, Boolean evolutionPresent, CodeElement evolutionHook) {
            this.commitId = commitId;
            this.date = date;

            this.before = before.getName();
            this.beforeLine = before.getLocation().getStartLine();
            this.beforePath = before.getLocation().getFilePath();

            this.after = after.getName();
            this.afterLine = after.getLocation().getStartLine();
            this.afterPath = after.getLocation().getFilePath();

            this.committer = committer;
            this.changes = changes;

            if(evolutionPresent){
                this.evolutionHook = evolutionHook.getName();
                this.evolutionHookLine = evolutionHook.getLocation().getStartLine();
                this.evolutionHookPath = evolutionHook.getLocation().getFilePath();
            }
        }
    }
}
