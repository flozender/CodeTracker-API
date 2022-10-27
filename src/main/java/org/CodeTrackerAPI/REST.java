package org.CodeTrackerAPI;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.uom.java.xmi.LocationInfo;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.text.*;
import org.codetracker.api.*;
import org.codetracker.change.Change;
import org.codetracker.change.EvolutionHook;
import org.codetracker.element.Attribute;
import org.codetracker.element.Block;
import org.codetracker.element.Method;
import org.codetracker.element.Variable;
import org.codetracker.util.CodeElementLocator;
import org.codetracker.util.GitRepository;
import org.codetracker.util.IRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.kohsuke.github.GHIssueBuilder;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.refactoringminer.api.GitService;
import org.refactoringminer.util.GitServiceImpl;

public class REST {
    public static boolean checkReported = false;

  public static void main(String[] args) {
    PathHandler path = Handlers
      .path()
      .addPrefixPath(
        "/api",
        Handlers
          .routing()
          .get(
            "/track",
            exchange -> {
              Map<String, Deque<String>> params = exchange.getQueryParameters();
              String owner = params.get("owner").getFirst();
              String repoName = params.get("repoName").getFirst();
              String commitId = params.get("commitId").getFirst();
              String filePath = params.get("filePath").getFirst();
              String name = params.get("selection").getFirst();
              String latestCommitHash;

              Integer lineNumber = Integer.parseInt(
                params.get("lineNumber").getFirst()
              );

              String changes = "";
              GitService gitService = new GitServiceImpl();

              try (
                Repository repository = gitService.cloneIfNotExists(
                  "tmp/" + repoName,
                  "https://github.com/" + owner + "/" + repoName + ".git"
                )
              ) {
                try (Git git = new Git(repository)) {
                  PullResult call = git.pull().call();
                  System.out.println(
                    "Pulled from the remote repository: " + call
                  );
                  latestCommitHash =
                    git.log().setMaxCount(1).call().iterator().next().getName();
                }
                if ("master".equals(commitId)) {
                  commitId = latestCommitHash;
                }

                CodeElementLocator locator = new CodeElementLocator(
                  repository,
                  commitId,
                  filePath,
                  name,
                  lineNumber
                );
                CodeElement codeElement = locator.locate();

                if (codeElement == null) {
                  throw new Exception("Selected code element is invalid.");
                }

                String response =
                  "{\"repositoryName\": \"" +
                  owner +
                  "\"," +
                  "\"repositoryWebURL\": \"https://github.com/" +
                  owner +
                  "/" +
                  repoName +
                  ".git" +
                  "\"," +
                  "\"filePath\": \"" +
                  filePath +
                  "\"," +
                  "\"selectionType\": \"" +
                  codeElement.getClass().getSimpleName() +
                  "\"," +
                  "\"codeElementType\": \"" +
                  codeElement.getLocation().getCodeElementType() +
                  "\",";

                if (codeElement.getClass() == Method.class) {
                  changes =
                    CTMethod(
                      owner,
                      repository,
                      filePath,
                      commitId,
                      name,
                      lineNumber
                    );
                } else if (codeElement.getClass() == Variable.class) {
                  Variable variable = (Variable) codeElement;
                  response =
                    response +
                    "\"functionName\": \"" +
                    variable.getOperation().getName() +
                    "\"," +
                    "\"functionStartLine\": " +
                    variable.getOperation().getLocationInfo().getStartLine() +
                    ",";
                  changes =
                    CTVariable(
                      owner,
                      repository,
                      filePath,
                      commitId,
                      name,
                      lineNumber,
                      codeElement
                    );
                } else if (codeElement.getClass() == Attribute.class) {
                  changes =
                    CTAttribute(
                      owner,
                      repository,
                      filePath,
                      commitId,
                      name,
                      lineNumber
                    );
                } else if (codeElement.getClass() == Block.class) {
                  Block block = (Block) codeElement;
                  response =
                    response +
                    "\"functionName\": \"" +
                    block.getOperation().getName() +
                    "\"," +
                    "\"functionStartLine\": " +
                    block.getOperation().getLocationInfo().getStartLine() +
                    "," +
                    "\"blockStartLine\": " +
                    codeElement.getLocation().getStartLine() +
                    "," +
                    "\"blockEndLine\": " +
                    codeElement.getLocation().getEndLine() +
                    ",";
                  changes =
                    CTBlock(
                      owner,
                      repository,
                      filePath,
                      commitId,
                      codeElement,
                      false
                    );
                }

                response =
                  response +
                  "\"startCommitId\": \"" +
                  commitId +
                  "\"," +
                  "\"changes\": " +
                  changes +
                  "}";

                exchange
                  .getResponseHeaders()
                  .put(new HttpString("Access-Control-Allow-Origin"), "*")
                  .put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(response);
              } catch (Exception e) {
                System.out.println("Something went wrong: " + e);
              }
            }
          )
          .get(
            "/codeElementType",
            exchange -> {
              Map<String, Deque<String>> params = exchange.getQueryParameters();
              String owner = params.get("owner").getFirst();
              String repoName = params.get("repoName").getFirst();
              String commitId = params.get("commitId").getFirst();
              String filePath = params.get("filePath").getFirst();
              String name = params.get("selection").getFirst();
              String latestCommitHash;

              Integer lineNumber = Integer.parseInt(
                params.get("lineNumber").getFirst()
              );
              String response;
              GitService gitService = new GitServiceImpl();

              try (
                Repository repository = gitService.cloneIfNotExists(
                  "tmp/" + repoName,
                  "https://github.com/" + owner + "/" + repoName + ".git"
                )
              ) {
                try (Git git = new Git(repository)) {
                  PullResult call = git.pull().call();
                  System.out.println(
                    "Pulled from the remote repository: " + call
                  );
                  latestCommitHash =
                    git.log().setMaxCount(1).call().iterator().next().getName();
                }
                if ("master".equals(commitId)) {
                  commitId = latestCommitHash;
                }

                CodeElementLocator locator = new CodeElementLocator(
                  repository,
                  commitId,
                  filePath,
                  name,
                  lineNumber
                );
                CodeElement codeElement = locator.locate();

                if (codeElement == null) {
                  throw new Exception("Selected code element is invalid.");
                }
                if (codeElement.getClass() == Method.class) {
                  response = "{\"type\": \"Method\"}";
                } else if (codeElement.getClass() == Variable.class) {
                  response = "{\"type\": \"Variable\"}";
                } else if (codeElement.getClass() == Attribute.class) {
                  response = "{\"type\": \"Attribute\"}";
                } else if (codeElement.getClass() == Block.class) {
                  response = "{\"type\": \"Block\"}";
                } else {
                  response = "{\"type\": \"Invalid Element\"}";
                }

                exchange
                  .getResponseHeaders()
                  .put(new HttpString("Access-Control-Allow-Origin"), "*")
                  .put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(response);
              } catch (Exception e) {
                System.out.println("Something went wrong: " + e);
                exchange
                  .getResponseHeaders()
                  .put(new HttpString("Access-Control-Allow-Origin"), "*")
                  .put(Headers.CONTENT_TYPE, "text/plain");
                exchange
                  .getResponseSender()
                  .send("{\"type\": \"Invalid Element\"}");
              }
            }
          )
          .get(
            "/addToOracle",
            exchange -> {
              Map<String, Deque<String>> params = exchange.getQueryParameters();
              String commitId = params.get("commitId").getFirst();
              String commitURL = params.get("commitURL").getFirst();
              String diffKey = params.get("diffKey").getFirst();
              Boolean report = Boolean.parseBoolean(
                params.get("report").getFirst()
              );
              Boolean valid = Boolean.parseBoolean(
                params.get("valid").getFirst()
              );
              try {
                try {
                    File dir;
                    if (checkReported){
                        dir = new File(
                                "src/main/resources/oracle/block/training/invalid/test-reported"
                        );
                    } else {
                        dir = new File(
                                "src/main/resources/oracle/block/training/false"
                        );
                    }
                  File[] files = dir.listFiles(
                    new FileFilter() {
                      boolean first = true;

                      public boolean accept(final File pathname) {
                        if (first) {
                          first = false;
                          return true;
                        }
                        return false;
                      }
                    }
                  );
                  String fileName = files[0].getName();

                  String folderName = valid ? "valid" : "invalid";

                  File currentFile = files[0];

                  if (!valid & report) {
                    // create GitHub issue
                    try {
                      String data = FileUtils.readFileToString(
                        files[0],
                        "UTF-8"
                      );

                      ObjectMapper mapper = new ObjectMapper();

                      Map<String, Object> blockJSON = mapper.readValue(
                        data,
                        new TypeReference<Map<String, Object>>() {}
                      );
                      ArrayList<Map<String, Object>> expectedChanges = (ArrayList<Map<String, Object>>) blockJSON.get(
                        "expectedChanges"
                      );
                      List<Map<String, Object>> changes = expectedChanges
                        .stream()
                        .filter(
                          change -> change.get("commitId").equals(commitId)
                        )
                        .collect(Collectors.toList());

                      String changeType = (String) changes
                        .stream()
                        .map(change -> change.get("changeType"))
                        .reduce(
                          "",
                          (partialString, change) ->
                            (String) partialString + change + ", "
                        );
                      changeType =
                        changeType.substring(0, changeType.length() - 2);
                      String elementNameAfter = (String) changes
                        .get(0)
                        .get("elementNameAfter");
                      String elementFileAfter = (String) changes
                        .get(0)
                        .get("elementFileAfter");

                      String token = System.getenv("GITHUB_KEY");
                      GitHub gitHub = new GitHubBuilder()
                        .withOAuthToken(token)
                        .build();
                      GHRepository repository = gitHub.getRepository(
                        "jodavimehran/code-tracker"
                      );
                      GHIssueBuilder issue = repository.createIssue(
                        "Invalid Change History - " + elementNameAfter
                      );
                      issue.body(
                        String.format(
                          "In commit [`%s`](%s) and file `%s`, element `%s` was identified as being `%s`, which may not be accurate. " +
                          System.lineSeparator() +
                          System.lineSeparator() +
                          "Oracle filename: `%s`",
                          commitId,
                          commitURL + "?diff=split#" + diffKey,
                          elementFileAfter,
                          elementNameAfter,
                          changeType,
                          fileName
                        )
                      );
                      issue.create();
                      System.out.println(
                        "Issue created on repo jodavimehran/code-tracker: " +
                        fileName
                      );
                    } catch (Exception e) {
                      System.out.println("Bug report failed!" + e);
                    }
                  }

                  String newFileName =
                    "src/main/resources/oracle/block/training/" +
                    folderName +
                    "/" +
                    fileName;

                  if (!valid & report) {
                    newFileName =
                      "src/main/resources/oracle/block/training/" +
                      folderName +
                      "/reported/" +
                      fileName;
                  }

                  currentFile.renameTo(new File(newFileName));

                  System.out.println(
                    "File moved to " + folderName + ": " + fileName
                  );
                } catch (Exception e) {
                  e.getStackTrace();
                  System.out.println(e);
                }

                exchange
                  .getResponseHeaders()
                  .put(new HttpString("Access-Control-Allow-Origin"), "*")
                  .put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send("{\"success\": true}");
              } catch (Exception e) {
                System.out.println("Something went wrong: " + e);
                exchange
                  .getResponseHeaders()
                  .put(new HttpString("Access-Control-Allow-Origin"), "*")
                  .put(Headers.CONTENT_TYPE, "text/plain");
                exchange
                  .getResponseSender()
                  .send("{\"type\": \"Invalid Element\"}");
              }
            }
          )
          .get(
            "/getOracleData",
            exchange -> {
              try {
                  File dir;
                  if (checkReported){
                      dir = new File(
                              "src/main/resources/oracle/block/training/invalid/test-reported"
                      );
                  } else {
                      dir = new File(
                              "src/main/resources/oracle/block/training/false"
                      );
                  }

                  File[] files = dir.listFiles(
                  new FileFilter() {
                    boolean first = true;

                    public boolean accept(final File pathname) {
                      if (first) {
                        first = false;
                        return true;
                      }
                      return false;
                    }
                  }
                );
                  String response = FileUtils.readFileToString(
                  files[0],
                  StandardCharsets.UTF_8
                );
                exchange
                  .getResponseHeaders()
                  .put(new HttpString("Access-Control-Allow-Origin"), "*")
                  .put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(response);
              } catch (Exception e) {
                System.out.println("Something went wrong: " + e);
                exchange
                  .getResponseHeaders()
                  .put(new HttpString("Access-Control-Allow-Origin"), "*")
                  .put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send("No elements found");
              }
            }
          )
      );

    Undertow server = Undertow
      .builder()
      .addHttpListener(5000, "0.0.0.0")
      .setHandler(path)
      .build();

    server.start();
  }

  private static String CTMethod(
    String owner,
    Repository repository,
    String filePath,
    String commitId,
    String methodName,
    Integer lineNumber
  ) {
    ArrayList<CTHMethod> changeLog = new ArrayList<>();
    try {
      MethodTracker methodTracker = CodeTracker
        .methodTracker()
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
        System.out.println(
          "======================================================"
        );
        System.out.println("Commit ID: " + historyInfo.getCommitId());
        System.out.println(
          "Date: " +
          LocalDateTime.ofEpochSecond(
            historyInfo.getCommitTime(),
            0,
            ZoneOffset.UTC
          )
        );
        System.out.println(
          "Before: " + historyInfo.getElementBefore().getName()
        );
        System.out.println("After: " + historyInfo.getElementAfter().getName());
        for (Change change : historyInfo.getChangeList()) {
          if (
            change.getType().getTitle().equals(change.toString().toLowerCase())
          ) {
            System.out.println(
              WordUtils.capitalizeFully(change.getType().getTitle())
            );
            currentChanges.add(
              WordUtils.capitalizeFully(change.getType().getTitle())
            );
          } else {
            System.out.println(
              WordUtils.capitalizeFully(change.getType().getTitle()) +
              ": " +
              change
            );
            currentChanges.add(
              WordUtils.capitalizeFully(change.getType().getTitle()) +
              ": " +
              change
            );
          }
          evolutionPresent = change.getEvolutionHook().isPresent();
          if (evolutionPresent) {
            evolutionHook = change.getEvolutionHook().get().getElementBefore();
          }
        }

        CTHMethod currentElement = new CTHMethod(
          historyInfo.getCommitId(),
          LocalDateTime
            .ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC)
            .toString(),
          historyInfo.getElementBefore(),
          historyInfo.getElementAfter(),
          historyInfo.getCommitterName(),
          historyInfo.getCommitTime(),
          currentChanges,
          evolutionPresent,
          evolutionHook
        );
        changeLog.add(currentElement);
      }
      System.out.println(
        "======================================================"
      );
      ObjectMapper mapper = new ObjectMapper();
      mapper.setVisibility(
        PropertyAccessor.FIELD,
        JsonAutoDetect.Visibility.ANY
      );
      try {
        Collections.reverse(changeLog);
        String json = mapper.writeValueAsString(changeLog);
        return json;
      } catch (JsonProcessingException e) {
        e.printStackTrace();
      }
    } catch (Exception e) {
      return e.toString();
    }

    return "";
  }

  private static String CTVariable(
    String owner,
    Repository repository,
    String filePath,
    String commitId,
    String variableName,
    Integer variableLineNumber,
    CodeElement codeElement
  ) {
    ArrayList<CTHVariable> changeLog = new ArrayList<>();
    Variable variable = (Variable) codeElement;
    try {
      VariableTracker variableTracker = CodeTracker
        .variableTracker()
        .repository(repository)
        .filePath(filePath)
        .startCommitId(commitId)
        .methodName(variable.getOperation().getName())
        .methodDeclarationLineNumber(
          variable.getOperation().getLocationInfo().getStartLine()
        )
        .variableName(variableName)
        .variableDeclarationLineNumber(variableLineNumber)
        .build();

      History<Variable> variableHistory = variableTracker.track();

      for (History.HistoryInfo<Variable> historyInfo : variableHistory.getHistoryInfoList()) {
        ArrayList<String> currentChanges = new ArrayList<>();
        CodeElement evolutionHook = null;
        Boolean evolutionPresent = false;
        System.out.println(
          "======================================================"
        );
        System.out.println("Commit ID: " + historyInfo.getCommitId());
        System.out.println(
          "Date: " +
          LocalDateTime.ofEpochSecond(
            historyInfo.getCommitTime(),
            0,
            ZoneOffset.UTC
          )
        );
        System.out.println(
          "Before: " + historyInfo.getElementBefore().getName()
        );
        System.out.println("After: " + historyInfo.getElementAfter().getName());
        for (Change change : historyInfo.getChangeList()) {
          if (
            change.getType().getTitle().equals(change.toString().toLowerCase())
          ) {
            System.out.println(
              WordUtils.capitalizeFully(change.getType().getTitle())
            );
            currentChanges.add(
              WordUtils.capitalizeFully(change.getType().getTitle())
            );
          } else {
            System.out.println(
              WordUtils.capitalizeFully(change.getType().getTitle()) +
              ": " +
              change
            );
            currentChanges.add(
              WordUtils.capitalizeFully(change.getType().getTitle()) +
              ": " +
              change
            );
          }
          evolutionPresent = change.getEvolutionHook().isPresent();
          if (evolutionPresent) {
            evolutionHook = change.getEvolutionHook().get().getElementBefore();
          }
        }

        CTHVariable currentElement = new CTHVariable(
          historyInfo.getCommitId(),
          LocalDateTime
            .ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC)
            .toString(),
          historyInfo.getElementBefore(),
          historyInfo.getElementAfter(),
          historyInfo.getCommitterName(),
          historyInfo.getCommitTime(),
          currentChanges,
          evolutionPresent,
          evolutionHook
        );
        changeLog.add(currentElement);
      }
      System.out.println(
        "======================================================"
      );

      ObjectMapper mapper = new ObjectMapper();
      mapper.setVisibility(
        PropertyAccessor.FIELD,
        JsonAutoDetect.Visibility.ANY
      );
      try {
        Collections.reverse(changeLog);
        String json = mapper.writeValueAsString(changeLog);
        return json;
      } catch (JsonProcessingException e) {
        e.printStackTrace();
      }
    } catch (Exception e) {
      System.out.println(e);
      return e.toString();
    }

    return "";
  }

  private static String CTAttribute(
    String owner,
    Repository repository,
    String filePath,
    String commitId,
    String attributeName,
    Integer lineNumber
  ) {
    ArrayList<CTHAttribute> changeLog = new ArrayList<>();
    try {
      AttributeTracker attributeTracker = CodeTracker
        .attributeTracker()
        .repository(repository)
        .filePath(filePath)
        .startCommitId(commitId)
        .attributeName(attributeName)
        .attributeDeclarationLineNumber(lineNumber)
        .build();

      History<Attribute> attributeHistory = attributeTracker.track();

      for (History.HistoryInfo<Attribute> historyInfo : attributeHistory.getHistoryInfoList()) {
        ArrayList<String> currentChanges = new ArrayList<>();
        CodeElement evolutionHook = null;
        Boolean evolutionPresent = false;
        System.out.println(
          "======================================================"
        );
        System.out.println("Commit ID: " + historyInfo.getCommitId());
        System.out.println(
          "Date: " +
          LocalDateTime.ofEpochSecond(
            historyInfo.getCommitTime(),
            0,
            ZoneOffset.UTC
          )
        );
        System.out.println(
          "Before: " + historyInfo.getElementBefore().getName()
        );
        System.out.println("After: " + historyInfo.getElementAfter().getName());
        for (Change change : historyInfo.getChangeList()) {
          if (
            change.getType().getTitle().equals(change.toString().toLowerCase())
          ) {
            System.out.println(
              WordUtils.capitalizeFully(change.getType().getTitle())
            );
            currentChanges.add(
              WordUtils.capitalizeFully(change.getType().getTitle())
            );
          } else {
            System.out.println(
              WordUtils.capitalizeFully(change.getType().getTitle()) +
              ": " +
              change
            );
            currentChanges.add(
              WordUtils.capitalizeFully(change.getType().getTitle()) +
              ": " +
              change
            );
          }
          evolutionPresent = change.getEvolutionHook().isPresent();
          if (evolutionPresent) {
            evolutionHook = change.getEvolutionHook().get().getElementBefore();
          }
        }

        CTHAttribute currentElement = new CTHAttribute(
          historyInfo.getCommitId(),
          LocalDateTime
            .ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC)
            .toString(),
          historyInfo.getElementBefore(),
          historyInfo.getElementAfter(),
          historyInfo.getCommitterName(),
          historyInfo.getCommitTime(),
          currentChanges,
          evolutionPresent,
          evolutionHook
        );
        changeLog.add(currentElement);
      }
      System.out.println(
        "======================================================"
      );
      ObjectMapper mapper = new ObjectMapper();
      mapper.setVisibility(
        PropertyAccessor.FIELD,
        JsonAutoDetect.Visibility.ANY
      );
      try {
        Collections.reverse(changeLog);
        String json = mapper.writeValueAsString(changeLog);
        return json;
      } catch (JsonProcessingException e) {
        e.printStackTrace();
      }
    } catch (Exception e) {
      return e.toString();
    }

    return "";
  }

  private static String CTBlock(
    String owner,
    Repository repository,
    String filePath,
    String commitId,
    CodeElement codeElement,
    boolean oracleGeneration
  ) {
    ArrayList<Object> changeLog = new ArrayList<Object>();
    try {
      Block block = (Block) codeElement;
      BlockTracker blockTracker = CodeTracker
        .blockTracker()
        .repository(repository)
        .filePath(filePath)
        .startCommitId(commitId)
        .methodName(block.getOperation().getName())
        .methodDeclarationLineNumber(
          block.getOperation().getLocationInfo().getStartLine()
        )
        .codeElementType(codeElement.getLocation().getCodeElementType())
        .blockStartLineNumber(codeElement.getLocation().getStartLine())
        .blockEndLineNumber(codeElement.getLocation().getEndLine())
        .build();

      History<Block> blockHistory = blockTracker.track();

      for (History.HistoryInfo<Block> historyInfo : blockHistory.getHistoryInfoList()) {
        ArrayList<String> currentChanges = new ArrayList<>();
        CodeElement evolutionHook = null;
        Boolean evolutionPresent = false;
        System.out.println(
          "======================================================"
        );
        System.out.println("Commit ID: " + historyInfo.getCommitId());
        System.out.println(
          "Date: " +
          LocalDateTime.ofEpochSecond(
            historyInfo.getCommitTime(),
            0,
            ZoneOffset.UTC
          )
        );
        System.out.println(
          "Before: " + historyInfo.getElementBefore().getName()
        );
        System.out.println("After: " + historyInfo.getElementAfter().getName());

        if (oracleGeneration) {
          IRepository gitRepository = new GitRepository(repository);
          for (Change change : historyInfo.getChangeList()) {
            // if comment is the same as the title, no comment in object
            if (
              change
                .getType()
                .getTitle()
                .equals(change.toString().toLowerCase())
            ) {
              CTHBlockOracle currentElement = new CTHBlockOracle(
                gitRepository.getParentId(historyInfo.getCommitId()),
                historyInfo.getCommitId(),
                historyInfo.getCommitTime(),
                change.getType().getTitle(),
                historyInfo.getElementBefore(),
                historyInfo.getElementAfter()
              );
              changeLog.add(currentElement);
            } else {
              CTHBlockOracleComment currentElement = new CTHBlockOracleComment(
                gitRepository.getParentId(historyInfo.getCommitId()),
                historyInfo.getCommitId(),
                historyInfo.getCommitTime(),
                change.getType().getTitle(),
                historyInfo.getElementBefore(),
                historyInfo.getElementAfter(),
                change.toString().replaceAll("\t", " ")
              );

              changeLog.add(currentElement);
            }
          }
        } else {
          for (Change change : historyInfo.getChangeList()) {
            if (
              change
                .getType()
                .getTitle()
                .equals(change.toString().toLowerCase())
            ) {
              System.out.println(
                WordUtils.capitalizeFully(change.getType().getTitle())
              );
              currentChanges.add(
                WordUtils.capitalizeFully(change.getType().getTitle())
              );
            } else {
              System.out.println(
                WordUtils.capitalizeFully(change.getType().getTitle()) +
                ": " +
                change
              );
              currentChanges.add(
                WordUtils.capitalizeFully(change.getType().getTitle()) +
                ": " +
                change
              );
            }
            evolutionPresent = change.getEvolutionHook().isPresent();
            if (evolutionPresent) {
              evolutionHook =
                change.getEvolutionHook().get().getElementBefore();
            }
          }
          CTHBlock currentElement = new CTHBlock(
            historyInfo.getCommitId(),
            LocalDateTime
              .ofEpochSecond(historyInfo.getCommitTime(), 0, ZoneOffset.UTC)
              .toString(),
            historyInfo.getElementBefore(),
            historyInfo.getElementAfter(),
            historyInfo.getCommitterName(),
            historyInfo.getCommitTime(),
            currentChanges,
            evolutionPresent,
            evolutionHook
          );
          changeLog.add(currentElement);
        }
      }
      System.out.println(
        "======================================================"
      );

      ObjectMapper mapper = new ObjectMapper();
      mapper.setVisibility(
        PropertyAccessor.FIELD,
        JsonAutoDetect.Visibility.ANY
      );
      try {
        Collections.reverse(changeLog);
        String json = mapper.writeValueAsString(changeLog);
        return json;
      } catch (JsonProcessingException e) {
        e.printStackTrace();
      }
    } catch (Exception e) {
      System.out.println(e);
      return e.toString();
    }

    return "";
  }

  // CodeTracker History Method Element
  private static class CTHMethod {

    String commitId;
    String date;
    String before;
    Integer beforeLine;
    String beforePath;
    String after;
    Integer afterLine;
    String afterPath;
    String committer;
    Long commitTime;
    ArrayList<String> changes;
    String evolutionHook;
    Integer evolutionHookLine;
    String evolutionHookPath;
    String evolutionHookCommit;

    String type = "method";

    private CTHMethod(
      String commitId,
      String date,
      CodeElement before,
      CodeElement after,
      String committer,
      Long commitTime,
      ArrayList<String> changes,
      Boolean evolutionPresent,
      CodeElement evolutionHook
    ) {
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

      if (evolutionPresent) {
        this.evolutionHook = evolutionHook.getName().split("\\#")[1].split("\\(")[0];
        this.evolutionHookLine = evolutionHook.getLocation().getStartLine();
        this.evolutionHookPath = evolutionHook.getLocation().getFilePath();
        this.evolutionHookCommit = evolutionHook.getVersion().getId();
      }
    }
  }

  // CodeTracker History Variable Element
  private static class CTHVariable {

    String commitId;
    String date;
    String before;
    Integer beforeLine;
    String beforePath;
    String after;
    Integer afterLine;
    String afterPath;
    String committer;
    Long commitTime;
    ArrayList<String> changes;
    String evolutionHook;
    Integer evolutionHookLine;
    String evolutionHookPath;
    String evolutionHookCommit;
    String type = "variable";

    private CTHVariable(
      String commitId,
      String date,
      CodeElement before,
      CodeElement after,
      String committer,
      Long commitTime,
      ArrayList<String> changes,
      Boolean evolutionPresent,
      CodeElement evolutionHook
    ) {
      this.commitId = commitId;
      this.date = date;

      this.before = before.getName();
      this.beforeLine = before.getLocation().getStartLine();
      this.beforePath = before.getLocation().getFilePath();

      this.after = after.getName();
      this.afterLine = after.getLocation().getStartLine();
      this.afterPath = after.getLocation().getFilePath();
      System.out.println("ID: " + after.getIdentifierIgnoringVersion());

      this.committer = committer;
      this.changes = changes;

      if (evolutionPresent) {
        this.evolutionHook = evolutionHook.getName().split("\\$")[1].split("\\:")[0];
        this.evolutionHookLine = evolutionHook.getLocation().getStartLine();
        this.evolutionHookPath = evolutionHook.getLocation().getFilePath();
        this.evolutionHookCommit = evolutionHook.getVersion().getId();
      }
    }
  }

  // CodeTracker History Method Element
  private static class CTHAttribute {

    String commitId;
    String date;
    String before;
    Integer beforeLine;
    String beforePath;
    String after;
    Integer afterLine;
    String afterPath;
    String committer;
    Long commitTime;
    ArrayList<String> changes;
    String evolutionHook;
    Integer evolutionHookLine;
    String evolutionHookPath;
    String evolutionHookCommit;
    String type = "attribute";

    private CTHAttribute(
      String commitId,
      String date,
      CodeElement before,
      CodeElement after,
      String committer,
      Long commitTime,
      ArrayList<String> changes,
      Boolean evolutionPresent,
      CodeElement evolutionHook
    ) {
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

      if (evolutionPresent) {
        this.evolutionHook = evolutionHook.getName();
        this.evolutionHookLine = evolutionHook.getLocation().getStartLine();
        this.evolutionHookPath = evolutionHook.getLocation().getFilePath();
        this.evolutionHookCommit = evolutionHook.getVersion().getId();
      }
    }
  }

  // CodeTracker History Block Element
  private static class CTHBlock {

    String commitId;
    String date;
    String before;
    Integer beforeLine;
    String beforePath;
    String after;
    Integer afterLine;
    String afterPath;
    String committer;
    Long commitTime;
    ArrayList<String> changes;
    String evolutionHook;
    Integer evolutionHookLine;
    String evolutionHookPath;
    String evolutionHookCommit;
    String type = "block";

    private CTHBlock(
      String commitId,
      String date,
      CodeElement before,
      CodeElement after,
      String committer,
      Long commitTime,
      ArrayList<String> changes,
      Boolean evolutionPresent,
      CodeElement evolutionHook
    ) {
      this.commitId = commitId;
      this.date = date;

      this.before = before.getName();
      this.beforeLine = before.getLocation().getStartLine();
      this.beforePath = before.getLocation().getFilePath();

      this.after = after.getName();
      this.afterLine = after.getLocation().getStartLine();
      this.afterPath = after.getLocation().getFilePath();

      this.committer = committer;
      this.commitTime = commitTime;
      this.changes = changes;

      if (evolutionPresent) {
        this.evolutionHook = evolutionHook.getName().split("\\$")[1].split("\\(")[0];
        this.evolutionHookLine = evolutionHook.getLocation().getStartLine();
        this.evolutionHookPath = evolutionHook.getLocation().getFilePath();
        this.evolutionHookCommit = evolutionHook.getVersion().getId();
      }
    }
  }

  // CodeTracker History Block Element For Oracle Generation
  protected static class CTHBlockOracle {

    String parentCommitId;
    String commitId;
    Long commitTime;
    String changeType;
    String elementFileBefore;
    String elementNameBefore;
    String elementFileAfter;
    String elementNameAfter;

    protected CTHBlockOracle(
      String parentCommitId,
      String commitId,
      Long commitTime,
      String changeType,
      CodeElement before,
      CodeElement after
    ) {
      this.parentCommitId = parentCommitId;
      this.commitId = commitId;
      this.commitTime = commitTime;
      this.changeType = changeType;

      this.elementNameBefore = before.getName();
      this.elementFileBefore = before.getLocation().getFilePath();

      this.elementNameAfter = after.getName();
      this.elementFileAfter = after.getLocation().getFilePath();
    }
  }

  // CodeTracker History Block Element For Oracle Generation with Comment
  protected static class CTHBlockOracleComment {

    String parentCommitId;
    String commitId;
    Long commitTime;
    String changeType;
    String elementFileBefore;
    String elementNameBefore;
    String elementFileAfter;
    String elementNameAfter;
    String comment;

    protected CTHBlockOracleComment(
      String parentCommitId,
      String commitId,
      Long commitTime,
      String changeType,
      CodeElement before,
      CodeElement after,
      String comment
    ) {
      this.parentCommitId = parentCommitId;
      this.commitId = commitId;
      this.commitTime = commitTime;
      this.changeType = changeType;

      this.elementNameBefore = before.getName();
      this.elementFileBefore = before.getLocation().getFilePath();

      this.elementNameAfter = after.getName();
      this.elementFileAfter = after.getLocation().getFilePath();
      this.comment = comment;
    }
  }
}
