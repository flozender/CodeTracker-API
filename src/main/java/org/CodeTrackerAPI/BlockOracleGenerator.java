package org.CodeTrackerAPI;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.uom.java.xmi.LocationInfo.CodeElementType;
import gr.uom.java.xmi.decomposition.CompositeStatementObject;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import org.CodeTrackerAPI.REST.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.codetracker.api.*;
import org.codetracker.api.History.HistoryInfo;
import org.codetracker.change.Change;
import org.codetracker.element.Block;
import org.codetracker.element.Method;
import org.codetracker.util.CodeElementLocator;
import org.codetracker.util.GitRepository;
import org.codetracker.util.IRepository;
import org.eclipse.jgit.lib.Repository;
import org.refactoringminer.api.GitService;
import org.refactoringminer.util.GitServiceImpl;

public class BlockOracleGenerator {

  public static String oracleType = "training";
  public static HashMap<String, Integer> methodBlocks = new HashMap<>();

  public static void main(String[] args) {
    System.out.println("Generating Block Oracle...");
    try {
      // Reset log file
      File logFile = new File("src/main/resources/oracle/block/log.txt");
      FileWriter logger = new FileWriter(logFile);
      logger.write(
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) +
        " " +
        "Started oracle generation" +
        "\r\n"
      );
      logger.close();
      log("Block " + oracleType + " Data");
    } catch (Exception e) {
      handleError(e, 0);
      System.exit(1);
    }
    File trueFolder = new File(
      "src/main/resources/oracle/block/" + oracleType + "/true"
    );
    File falseFolder = new File(
      "src/main/resources/oracle/block/" + oracleType + "/false"
    );
    File validFolder = new File(
      "src/main/resources/oracle/block/" + oracleType + "/valid"
    );
    File invalidFolder = new File(
      "src/main/resources/oracle/block/" + oracleType + "/invalid"
    );
    File invalidReportedFolder = new File(
      "src/main/resources/oracle/block/" + oracleType + "/invalid/reported"
    );

    HashMap<String, Integer> validFiles = new HashMap<String, Integer>();
    HashMap<String, Integer> invalidFiles = new HashMap<String, Integer>();
    HashMap<String, Integer> invalidReportedFiles = new HashMap<String, Integer>();

    for (File file : validFolder.listFiles()) {
      if (!file.isFile()) {
        continue;
      }
      ObjectMapper mapper = new ObjectMapper();
      try {
        String data = FileUtils.readFileToString(file, "UTF-8");
        int hashCode = data
          .replaceAll(" ", "")
          .replaceAll("\r\n", "")
          .hashCode();
        Map<String, Object> blockJSON = mapper.readValue(
          data,
          new TypeReference<Map<String, Object>>() {}
        );
        String startCommitId = (String) blockJSON.get("startCommitId");
        String blockKey = (String) blockJSON.get("blockKey");
        validFiles.put(startCommitId + "-" + blockKey, hashCode);
      } catch (Exception e) {
        handleError(e, 1);
      }
    }

    for (File file : invalidFolder.listFiles()) {
      if (!file.isFile()) {
        continue;
      }
      ObjectMapper mapper = new ObjectMapper();
      try {
        String data = FileUtils.readFileToString(file, "UTF-8");
        int hashCode = data
          .replaceAll(" ", "")
          .replaceAll("\r\n", "")
          .hashCode();
        Map<String, Object> blockJSON = mapper.readValue(
          data,
          new TypeReference<Map<String, Object>>() {}
        );
        String startCommitId = (String) blockJSON.get("startCommitId");
        String blockKey = (String) blockJSON.get("blockKey");
        invalidFiles.put(startCommitId + "-" + blockKey, hashCode);
      } catch (Exception e) {
        handleError(e, 2);
      }
    }

    for (File file : invalidReportedFolder.listFiles()) {
      if (!file.isFile()) {
        continue;
      }
      ObjectMapper mapper = new ObjectMapper();
      try {
        String data = FileUtils.readFileToString(file, "UTF-8");
        int hashCode = data
          .replaceAll(" ", "")
          .replaceAll("\r\n", "")
          .hashCode();
        Map<String, Object> blockJSON = mapper.readValue(
          data,
          new TypeReference<Map<String, Object>>() {}
        );
        String startCommitId = (String) blockJSON.get("startCommitId");
        String blockKey = (String) blockJSON.get("blockKey");
        invalidReportedFiles.put(startCommitId + "-" + blockKey, hashCode);
      } catch (Exception e) {
        handleError(e, 3);
      }
    }

    try {
      FileUtils.cleanDirectory(
        new File("src/main/resources/oracle/block/" + oracleType)
      );
      trueFolder.mkdirs();
      falseFolder.mkdirs();
      validFolder.mkdirs();
      invalidFolder.mkdirs();
      invalidReportedFolder.mkdirs();
    } catch (Exception e) {
      handleError(e, 4);
    }
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
            "tmp/" + repoName,
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

          List<CompositeStatementObject> composites = method
            .getUmlOperation()
            .getBody()
            .getCompositeStatement()
            .getInnerNodes();

          for (CompositeStatementObject composite : composites) {
            Block block = Block.of(composite, method);

            boolean isInvalid = block
              .getComposite()
              .getLocationInfo()
              .getCodeElementType()
              .equals(CodeElementType.BLOCK);
            if (isInvalid) {
              continue;
            }

            BlockTracker blockTracker = CodeTracker
              .blockTracker()
              .repository(repository)
              .filePath(block.getFilePath())
              .startCommitId(commitId)
              .methodName(block.getOperation().getName())
              .methodDeclarationLineNumber(
                block.getOperation().getLocationInfo().getStartLine()
              )
              .codeElementType(
                block.getComposite().getLocationInfo().getCodeElementType()
              )
              .blockStartLineNumber(
                block.getComposite().getLocationInfo().getStartLine()
              )
              .blockEndLineNumber(
                block.getComposite().getLocationInfo().getEndLine()
              )
              .build();

            History<Block> blockHistory = blockTracker.track();
            List<HistoryInfo<Block>> blockHistoryInfo = blockHistory.getHistoryInfoList();
            if (blockHistoryInfo.size() == 0) {
              log(
                "BlockHistoryInfo zero size: " +
                repositoryWebURL +
                " " +
                block.getFilePath() +
                " " +
                block.getOperation().getName() +
                " " +
                block.getComposite().getLocationInfo().getCodeElementType() +
                " " +
                block.getComposite().getLocationInfo().getStartLine() +
                " " +
                block.getComposite().getLocationInfo().getEndLine() +
                commitId
              );
            }
            if (methodCommits.size() == 0) {
              log(
                "methodCommits zero size: " +
                repositoryWebURL +
                " " +
                block.getFilePath() +
                " " +
                block.getOperation().getName() +
                " " +
                block.getComposite().getLocationInfo().getCodeElementType() +
                " " +
                block.getComposite().getLocationInfo().getStartLine() +
                " " +
                block.getComposite().getLocationInfo().getEndLine() +
                commitId
              );
            }
            HistoryInfo<Block> firstChange = blockHistoryInfo.get(0);
            boolean validHistory = true;
            // check if the block history reaches the method introduction commit
            if (firstChange.getCommitId().equals(methodCommits.get(0))) {
              //  Check if block history is a subset of method history
              for (HistoryInfo<Block> historyInfo : blockHistoryInfo) {
                if (!methodCommits.contains(historyInfo.getCommitId())) {
                  validHistory = false;
                }
              }
            } else {
              validHistory = false;
            }

            createOracleEntry(
              repository,
              repoName,
              repositoryWebURL,
              filePath,
              method,
              block,
              commitId,
              blockHistoryInfo,
              oracleType,
              validHistory,
              validFiles,
              invalidFiles,
              invalidReportedFiles
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
    CodeElement codeElement,
    String commitId,
    List<HistoryInfo<Block>> blockHistoryInfo,
    String oracleType,
    Boolean valid,
    HashMap<String, Integer> validFiles,
    HashMap<String, Integer> invalidFiles,
    HashMap<String, Integer> invalidReportedFiles
  ) {
    String changes = null;
    ArrayList<CTHBlockOracleComment> changeLog = new ArrayList<CTHBlockOracleComment>();
    IRepository gitRepository = new GitRepository(repository);

    for (HistoryInfo<Block> historyInfo : blockHistoryInfo) {
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

    Block block = (Block) codeElement;

    response =
      response +
      "\"functionName\": \"" +
      block.getOperation().getName() +
      "\"," +
      "\"functionKey\": \"" +
      method.getName() +
      "\"," +
      "\"functionStartLine\": " +
      method.getLocation().getStartLine() +
      "," +
      "\"blockType\": \"" +
      codeElement.getLocation().getCodeElementType() +
      "\"," +
      "\"blockKey\": \"" +
      codeElement.getName() +
      "\"," +
      "\"blockStartLine\": " +
      codeElement.getLocation().getStartLine() +
      "," +
      "\"blockEndLine\": " +
      codeElement.getLocation().getEndLine() +
      "," +
      "\"startCommitId\": \"" +
      commitId +
      "\"," +
      "\"expectedChanges\": " +
      changes +
      "}";

    String folderName = valid ? "true" : "false";

    String fileKey = commitId + "-" + codeElement.getName();
    int hashCode = response
      .replaceAll(" ", "")
      .replaceAll("\r\n", "")
      .hashCode();

    if (folderName == "false") {
      if (validFiles.containsKey(fileKey)) {
        if (validFiles.get(fileKey) == hashCode) {
          folderName = "valid";
        }
      } else if (invalidFiles.containsKey(fileKey)) {
        if (invalidFiles.get(fileKey) == hashCode) {
          folderName = "invalid";
        }
      } else if (invalidReportedFiles.containsKey(fileKey)) {
        folderName = "invalid/reported";
      }
    }

    try {
      // Creates a Writer using FileWriter
      String fileName =
        "src/main/resources/oracle/block/" +
        oracleType +
        "/" +
        folderName +
        "/" +
        repositoryName +
        "-" +
        block
          .getOperation()
          .getClassName()
          .split("\\.")[block
            .getOperation()
            .getClassName()
            .split("\\.")
            .length -
          1] +
        "-" +
        block.getOperation().getName() +
        "-" +
        codeElement.getLocation().getCodeElementType();

      String absoluteFileName =
        repositoryName +
        "-" +
        block
          .getOperation()
          .getClassName()
          .split("\\.")[block
            .getOperation()
            .getClassName()
            .split("\\.")
            .length -
          1] +
        "-" +
        block.getOperation().getName() +
        "-" +
        codeElement.getLocation().getCodeElementType();
      // check if file already exists, add numerals at the end if true
      if (methodBlocks.containsKey(absoluteFileName)) {
        log(
          "Key contained: " +
          absoluteFileName +
          " -> " +
          methodBlocks.get(absoluteFileName)
        );
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
        log("No key yet: " + absoluteFileName);
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
      File logFile = new File("src/main/resources/oracle/block/log.txt");
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
