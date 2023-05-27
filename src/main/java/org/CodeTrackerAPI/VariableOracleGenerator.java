package org.CodeTrackerAPI;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.uom.java.xmi.LocationInfo.CodeElementType;
import gr.uom.java.xmi.decomposition.CompositeStatementObject;
import gr.uom.java.xmi.decomposition.VariableDeclaration;
import org.CodeTrackerAPI.REST.CTHBlockOracle;
import org.CodeTrackerAPI.REST.CTHBlockOracleComment;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.codetracker.api.*;
import org.codetracker.api.History.HistoryInfo;
import org.codetracker.change.Change;
import org.codetracker.element.Block;
import org.codetracker.element.Method;
import org.codetracker.element.Variable;
import org.codetracker.util.CodeElementLocator;
import org.codetracker.util.GitRepository;
import org.codetracker.util.IRepository;
import org.eclipse.jgit.lib.Repository;
import org.refactoringminer.api.GitService;
import org.refactoringminer.util.GitServiceImpl;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class VariableOracleGenerator {

  public static String oracleType = "training";
  public static HashMap<String, Integer> methodBlocks = new HashMap<>();

  public static void main(String[] args) {
    System.out.println("Generating Variable Oracle...");

    File folder = new File("src/main/resources/oracle/method/" + oracleType);
    File[] listOfFiles = folder.listFiles();

    for (File file : listOfFiles) {
      if (!file.isFile()) {
        continue;
      }

      ObjectMapper mapper = new ObjectMapper();
      try {
        String data = FileUtils.readFileToString(file, "UTF-8");

        Map<String, Object> methodJSON = mapper.readValue(
          data,
          new TypeReference<Map<String, Object>>() {}
        );

        String repoName = (String) methodJSON.get("repositoryName");
        String repoOwner = methodJSON.get("repositoryWebURL").toString().split("/")[3];
        String repositoryWebURL = (String) methodJSON.get("repositoryWebURL");
        String commitId = (String) methodJSON.get("startCommitId");
        String filePath = (String) methodJSON.get("filePath");
        String methodName = (String) methodJSON.get("functionName");
        Integer lineNumber = (Integer) methodJSON.get("functionStartLine");
        ArrayList<Map<String, String>> expectedChanges = (ArrayList<Map<String, String>>) methodJSON.get(
          "expectedChanges"
        );

        GitService gitService = new GitServiceImpl();

        try (
          Repository repository = gitService.cloneIfNotExists(
            "tmp/" + repoOwner + "/" + repoName,
            repositoryWebURL
          )
        ) {
          CodeElementLocator locator = new CodeElementLocator(
            repository,
            commitId,
            filePath,
            methodName,
            lineNumber
          );
          CodeElement codeElement = locator.locate();

          if (codeElement == null) {
            throw new Exception("Selected code element is invalid.");
          }
          Method method = (Method) codeElement;

          List<String> methodCommits = expectedChanges
            .stream()
            .map(historyObject -> historyObject.get("commitId"))
            .collect(Collectors.toList());

          Collections.reverse(methodCommits);

          List<VariableDeclaration> variables = method
            .getUmlOperation().getAllVariableDeclarations();

          for (VariableDeclaration variableDeclaration : variables) {

            VariableTracker variableTracker = CodeTracker
              .variableTracker()
              .repository(repository)
              .filePath(method.getFilePath())
              .startCommitId(commitId)
              .methodName(methodName)
              .methodDeclarationLineNumber(method.getLocation().getStartLine())
              .variableName(variableDeclaration.getVariableName())
              .variableDeclarationLineNumber(variableDeclaration.getLocationInfo().getStartLine())
              .build();

            History<Variable> variableHistory = variableTracker.track();
            List<HistoryInfo<Variable>> variableHistoryInfo = variableHistory.getHistoryInfoList();



            createOracleEntry(
              repository,
              repoName,
              repositoryWebURL,
              filePath,
              method,
              variableDeclaration,
              commitId,
              variableHistoryInfo,
              oracleType
            );
          }
        } catch (Exception e) {
          handleError(e, 5);
        }
      } catch (Exception e) {
        handleError(e, 6);
      }
    }
    log(
      new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) +
      " " +
      "Generation finished successfully."
    );
  }

  public static void createOracleEntry(
    Repository repository,
    String repositoryName,
    String repositoryWebURL,
    String filePath,
    Method method,
    VariableDeclaration variableDeclaration,
    String commitId,
    List<HistoryInfo<Variable>> variableHistoryInfo,
    String oracleType) {

    String changes = null;
    ArrayList<CTHBlockOracleComment> changeLog = new ArrayList<CTHBlockOracleComment>();
    IRepository gitRepository = new GitRepository(repository);

    for (HistoryInfo<Variable> historyInfo : variableHistoryInfo) {
      for (Change change : historyInfo.getChangeList()) {
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

    ObjectMapper mapper = new ObjectMapper();
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    try {
//      changeLog.sort(
//        Comparator
//          .comparing(CTHBlockOracleComment::getCommitTime)
//          .thenComparing(CTHBlockOracleComment::getChangeType)
//      );

      Collections.reverse(changeLog);

      ArrayList<Object> changeLogCasted = changeLog
        .stream()
        .map(change -> {
          // if comment is the same as the title, no comment in object
          if (
            change.getChangeType().equals(change.getComment().toLowerCase())
          ) {
            return new CTHBlockOracle(
              change.parentCommitId,
              change.commitId,
              change.commitTime,
              change.changeType,
              change.elementNameBefore,
              change.elementFileBefore,
              change.elementNameAfter,
              change.elementFileAfter
            );
          }
          return change;
        })
        .collect(Collectors.toCollection(ArrayList::new));

      String json = mapper.writeValueAsString(changeLogCasted);
      changes = json;
    } catch (JsonProcessingException e) {
      handleError(e, 7);
    }
    String response =
      "{\"repositoryName\": \"" +
      repositoryName +
      "\"," +
      "\"repositoryWebURL\": \"" +
      repositoryWebURL +
      "\"," +
      "\"filePath\": \"" +
      filePath +
      "\",";

    Variable variable = Variable.of(variableDeclaration, method);

    response =
      response +
      "\"functionName\": \"" +
      method.getUmlOperation().getName() +
      "\"," +
      "\"functionKey\": \"" +
      method.getName() +
      "\"," +
      "\"functionStartLine\": " +
      method.getLocation().getStartLine() +
      "," +
      "\"variableName\": \"" +
      variableDeclaration.getVariableName() +
      "\"," +
      "\"variableKey\": \"" +
      variable.getName() +
      "\"," +
      "\"variableStartLine\": " +
      variableDeclaration.getLocationInfo().getStartLine() +
      "," +
      "\"startCommitId\": \"" +
      commitId +
      "\"," +
      "\"expectedChanges\": " +
      changes +
      "}";

    String folderName = "true";

    String fileKey = commitId + "-" + method.getName();


    try {
      // Creates a Writer using FileWriter
      String fileName =
        "src/main/resources/oracle/new-variable/" +
        oracleType +
        "/" +
        folderName +
        "/" +
        repositoryName +
        "-" +
        method
          .getUmlOperation()
          .getClassName()
          .split("\\.")[method
            .getUmlOperation()
            .getClassName()
            .split("\\.")
            .length -
          1] +
        "-" +
        method.getUmlOperation().getName() +
        "-" +
        variableDeclaration.getVariableName();

      String absoluteFileName =
        repositoryName +
        "-" +
        method
          .getUmlOperation()
          .getClassName()
          .split("\\.")[method
            .getUmlOperation()
            .getClassName()
            .split("\\.")
            .length -
          1] +
        "-" +
        method.getUmlOperation().getName() +
        "-" +
        variableDeclaration.getVariableName();
      // check if file already exists, add numerals at the end if true
      if (methodBlocks.containsKey(absoluteFileName)) {

        fileName =
          fileName +
          "-" +
          methodBlocks.get(absoluteFileName).toString() +
          ".json";
        methodBlocks.put(
          absoluteFileName,
          methodBlocks.get(absoluteFileName) + 1
        );
      } else {

        fileName = fileName + ".json";
        methodBlocks.put(absoluteFileName, 1);
      }

      FileWriter output = new FileWriter(fileName);

      // Writes the program to file
      output.write(response);
      System.out.println("Data is written to file: " + fileName);
      log("Data is written to file: " + fileName);

      // Closes the writer
      output.close();
    } catch (Exception e) {
      handleError(e, 8);
    }
  }

  public static void log(String message) {
    try {
      File logFile = new File("src/main/resources/oracle/new-variable/log.txt");
      FileWriter logger = new FileWriter(logFile, true);
      logger.write(message + "\r\n");
      logger.close();
    } catch (Exception e) {
      System.out.println("Case 0 - Failed: logger failed");
    }
  }

  public static void handleError(Exception e, Integer number) {
    System.out.println("Case " + number.toString() + " - Failed: " + e);
    e.printStackTrace();
    String stacktrace = ExceptionUtils.getStackTrace(e);
    log("Case " + number.toString() + " - Failed: " + e);
    log(stacktrace);
  }
}
