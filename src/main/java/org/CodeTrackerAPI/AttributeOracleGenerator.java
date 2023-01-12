package org.CodeTrackerAPI;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.uom.java.xmi.*;
import gr.uom.java.xmi.decomposition.AbstractCodeFragment;
import gr.uom.java.xmi.decomposition.CompositeStatementObject;
import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.codetracker.BaseTracker;
import org.codetracker.api.*;
import org.codetracker.change.Change;
import org.codetracker.element.Attribute;
import org.codetracker.element.Block;
import org.codetracker.element.Method;
import org.codetracker.util.CodeElementLocator;
import org.codetracker.util.GitRepository;
import org.codetracker.util.IRepository;
import org.eclipse.jgit.lib.Repository;
import org.refactoringminer.api.GitService;
import org.refactoringminer.util.GitServiceImpl;

public class AttributeOracleGenerator {

  public static void main(String[] args) {
    String oracleType = "test";
    File folder = new File("src/main/resources/oracle/method/" + oracleType);
    File[] listOfFiles = folder.listFiles();

    assert listOfFiles != null;
    for (File file : listOfFiles) {
      if (!file.isFile()) {
        continue;
      }
      System.out.println("FILE IS " + file);
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

          VariableDeclarationContainer operation = method.getUmlOperation();

          UMLModel umlModel = BaseTracker.getUMLModel(
            repository,
            commitId,
            Collections.singleton(method.getLocation().getFilePath())
          );

          for (UMLClass umlClass : umlModel.getClassList()) {
            for (UMLAttribute attribute : umlClass.getAttributes()) {
              Set<AbstractCodeFragment> list = attribute
                .getVariableDeclaration()
                .getStatementsInScopeUsingVariable();

              Boolean valid = false;
              for (AbstractCodeFragment statementInScopeUsingVariable : list) {
                Boolean inOperation = operation
                  .getLocationInfo()
                  .subsumes(statementInScopeUsingVariable.getLocationInfo());
                if (inOperation) {
                  valid = true;
                  break;
                }
              }

              if (valid) {

                System.out.println("Valid attribute: "+ attribute.getName());
                // create here
                AttributeTracker attributeTracker = CodeTracker
                  .attributeTracker()
                  .repository(repository)
                  .filePath(attribute.getLocationInfo().getFilePath())
                  .startCommitId(commitId)
                  .attributeName(attribute.getName())
                  .attributeDeclarationLineNumber(
                    attribute.getLocationInfo().getStartLine()
                  )
                  .build();
                GitRepository iRepository = new GitRepository(repository);

                Attribute attr = Attribute.of(
                        attribute,
                        iRepository.getVersion(commitId)
                );
                History<Attribute> attributeHistory = attributeTracker.track();

                List<History.HistoryInfo<Attribute>> attributeHistoryInfo = attributeHistory.getHistoryInfoList();
                System.out.println("History is " + attributeHistoryInfo);
                createOracleEntry(
                  repository,
                  repoName,
                  repositoryWebURL,
                  filePath,
                  attribute,
                  commitId,
                  attributeHistoryInfo,
                  oracleType
                );
              }
            }
          }
        }
      } catch (Exception e) {
        System.out.println("1 - " + e);
      }
    }
  }

  public static void createOracleEntry(
    Repository repository,
    String repositoryName,
    String repositoryWebURL,
    String filePath,
    UMLAttribute umlAttribute,
    String commitId,
    List<History.HistoryInfo<Attribute>> attributeHistoryInfo,
    String oracleType
  ) {
    GitRepository iRepository = new GitRepository(repository);


    String changes = null;
    ArrayList<REST.CTHBlockOracleComment> changeLog = new ArrayList<REST.CTHBlockOracleComment>();
    IRepository gitRepository = new GitRepository(repository);

    for (History.HistoryInfo<Attribute> historyInfo : attributeHistoryInfo) {
      for (Change change : historyInfo.getChangeList()) {
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

    ObjectMapper mapper = new ObjectMapper();
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    try {
      changeLog.sort(
        Comparator
          .comparing(REST.CTHBlockOracleComment::getCommitTime)
          .thenComparing(REST.CTHBlockOracleComment::getChangeType)
      );

      Collections.reverse(changeLog);

      ArrayList<Object> changeLogCasted = changeLog
        .stream()
        .map(change -> {
          // if comment is the same as the title, no comment in object
          if (
            change.getChangeType().equals(change.getComment().toLowerCase())
          ) {
            return new REST.CTHBlockOracle(
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
      System.err.println("2 - " +e);
    }
    String response =
      "{\"repositoryName\": \"" +
      repositoryName +
      "\"," +
      "\"repositoryWebURL\": \"" +
      repositoryWebURL +
      "\"," +
      "\"startCommitId\": \"" +
      commitId +
      "\"," +
      "\"filePath\": \"" +
      filePath +
      "\",";

    Attribute attribute = Attribute.of(
            umlAttribute,
            iRepository.getVersion(commitId)
    );

    response =
      response +
      "\"attributeName\": \"" +
       umlAttribute.getName() +
      "\"," +
      "\"attributeKey\": \"" +
      attribute.getName() +
      "\"," +
      "\"attributeDeclarationLine\": " +
       umlAttribute.getLocationInfo().getStartLine() +
      "," +
      "\"expectedChanges\": " +
      changes +
      "}";

    try {
      // Creates a Writer using FileWriter
      String fileName =
        "src/main/resources/oracle/attribute/" +
        oracleType +
        "/" +
        repositoryName +
        "-" +
                umlAttribute
          .getClassName()
          .split("\\.")[umlAttribute
            .getClassName()
            .split("\\.")
            .length -
          1] +
        "-" +
                umlAttribute.getName();

      fileName = fileName + ".json";

      FileWriter output = new FileWriter(fileName);

      // Writes the program to file
      output.write(response);
      System.out.println("Data is written to file: " + fileName);

      // Closes the writer
      output.close();
    } catch (Exception e) {
      System.err.println("3 - " +e);
    }
  }
}
