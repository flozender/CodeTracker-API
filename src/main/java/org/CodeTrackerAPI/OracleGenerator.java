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
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
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

public class OracleGenerator {

  public static void main(String[] args) {
    System.out.println("Generating Block Oracle...");
    String oracleType = "training";
    File validFolder = new File(
      "src/main/resources/oracle/block/" + oracleType + "/valid"
    );
    File invalidFolder = new File(
      "src/main/resources/oracle/block/" + oracleType + "/invalid"
    );

    HashMap<String, Integer> validFiles = new HashMap<String, Integer>();
    HashMap<String, Integer> invalidFiles = new HashMap<String, Integer>();

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
        System.out.println(e);
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
        System.out.println(e);
      }
    }

    try {
      FileUtils.cleanDirectory(
        new File("src/main/resources/oracle/block/" + oracleType + "/true")
      );
      FileUtils.cleanDirectory(
        new File("src/main/resources/oracle/block/" + oracleType + "/false")
      );
      FileUtils.cleanDirectory(
        new File("src/main/resources/oracle/block/" + oracleType + "/valid")
      );
      FileUtils.cleanDirectory(
        new File("src/main/resources/oracle/block/" + oracleType + "/invalid")
      );
    } catch (Exception e) {
      e.getStackTrace();
      System.out.println(e);
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

          MethodTracker methodTracker = CodeTracker
            .methodTracker()
            .repository(repository)
            .filePath(filePath)
            .startCommitId(commitId)
            .methodName(methodName)
            .methodDeclarationLineNumber(lineNumber)
            .build();

          History<Method> methodHistory = methodTracker.track();
          List<String> methodCommits = methodHistory
            .getHistoryInfoList()
            .stream()
            .map(historyObject -> historyObject.getCommitId())
            .collect(Collectors.toList());

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
              invalidFiles
            );
          }
        } catch (Exception e) {
          System.out.println("Something went wrong: " + e);
        }
      } catch (Exception e) {
        System.out.println("Something went wrong: " + e);
      }
    }
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
    HashMap<String, Integer> invalidFiles
  ) {
    String changes = null;
    ArrayList<Object> changeLog = new ArrayList<Object>();
    IRepository gitRepository = new GitRepository(repository);
    for (HistoryInfo<Block> historyInfo : blockHistoryInfo) {
      for (Change change : historyInfo.getChangeList()) {
        // if comment is the same as the title, no comment in object
        if (
          change.getType().getTitle().equals(change.toString().toLowerCase())
        ) {
          REST.CTHBlockOracle currentElement = new REST.CTHBlockOracle(
            gitRepository.getParentId(historyInfo.getCommitId()),
            historyInfo.getCommitId(),
            historyInfo.getCommitTime(),
            change.getType().getTitle(),
            historyInfo.getElementBefore(),
            historyInfo.getElementAfter()
          );
          changeLog.add(currentElement);
        } else {
          REST.CTHBlockOracleComment currentElement = new REST.CTHBlockOracleComment(
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
    }

    ObjectMapper mapper = new ObjectMapper();
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    try {
      Collections.reverse(changeLog);
      String json = mapper.writeValueAsString(changeLog);
      changes = json;
    } catch (JsonProcessingException e) {
      e.printStackTrace();
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

      // check if file already exists, add numerals at the end if true
      if (new File(fileName + ".json").isFile()) {
        Integer i = 1;
        while (new File(fileName + "-" + i.toString() + ".json").isFile()) {
          i++;
        }
        fileName = fileName + "-" + i.toString() + ".json";
      } else {
        fileName = fileName + ".json";
      }

      FileWriter output = new FileWriter(fileName);

      // Writes the program to file
      output.write(response);
      System.out.println("Data is written to file: " + fileName);

      // Closes the writer
      output.close();
    } catch (Exception e) {
      e.getStackTrace();
      System.out.println(e);
    }
  }
}
