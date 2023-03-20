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

    File folder = new File("src/main/resources/oracle/block/" + oracleType);
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
        String blockTypeString = (String) methodJSON.get("blockType");
        Integer blockStartLine = (Integer) methodJSON.get("blockStartLine");
        ArrayList<Map<String, String>> expectedChanges = (ArrayList<Map<String, String>>) methodJSON.get(
          "expectedChanges"
        );

        int expectedChangesLength = expectedChanges.size();
        if (!expectedChanges.get(expectedChangesLength - 1).get("comment").contains("Extract Method")){
//          System.out.println("skipping " + file.getName());
          continue;
        }

        System.out.println(">>>>> file: " + file.getName());

        GitService gitService = new GitServiceImpl();

        try (
          Repository repository = gitService.cloneIfNotExists(
                      "tmp/" +repoOwner+"/" +repoName,
                      repositoryWebURL
              )
        ) {


          CodeElementLocator locator = new CodeElementLocator(
            repository,
            commitId,
            filePath,
            CodeElementType.valueOf(blockTypeString).getName(),
            blockStartLine
          );
          CodeElement codeElement = locator.locate();

          if (codeElement == null) {
            throw new Exception("Selected code element is invalid.");
          }
          Block block = (Block) codeElement;


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

            createOracleEntry(repository, methodJSON, file, block, blockHistoryInfo);

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
          Map<String, Object> methodJSON,
          File file,
          Block block,
          List<HistoryInfo<Block>> blockHistoryInfo
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
      methodJSON.get("repositoryName") +
      "\"," +
      "\"repositoryWebURL\": \"" +
              methodJSON.get("repositoryWebURL") +
      "\"," +
      "\"filePath\": \"" +
              methodJSON.get("filePath") +
      "\",";

    response =
      response +
      "\"functionName\": \"" +
              methodJSON.get("functionName") +
      "\"," +
      "\"functionKey\": \"" +
              methodJSON.get("functionKey") +
      "\"," +
      "\"functionStartLine\": " +
              methodJSON.get("functionStartLine") +
      "," +
      "\"blockType\": \"" +
              methodJSON.get("blockType") +
      "\"," +
      "\"blockKey\": \"" +
              methodJSON.get("blockKey") +
      "\"," +
      "\"blockStartLine\": " +
              methodJSON.get("blockStartLine") +
      "," +
      "\"blockEndLine\": " +
              methodJSON.get("blockEndLine") +
      "," +
      "\"startCommitId\": \"" +
              methodJSON.get("startCommitId") +
      "\"," +
      "\"expectedChanges\": " +
      changes +
      "}";


    try {
      // Creates a Writer using FileWriter
      String fileName = file.getPath();

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
