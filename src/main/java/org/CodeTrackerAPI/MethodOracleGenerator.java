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
import org.codetracker.element.BaseCodeElement;
import org.codetracker.element.Block;
import org.codetracker.element.Method;
import org.codetracker.util.CodeElementLocator;
import org.codetracker.util.GitRepository;
import org.codetracker.util.IRepository;
import org.codetracker.util.Util;
import org.eclipse.jgit.lib.Repository;
import org.refactoringminer.api.GitService;
import org.refactoringminer.util.GitServiceImpl;

public class MethodOracleGenerator {

    public static void main(String[] args) {
        String oracleType = "training";
        File folder = new File("src/main/resources/oracle/method/" + oracleType);
        File[] listOfFiles = folder.listFiles();

        assert listOfFiles != null;
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
                                "tmp/" + repoOwner + "/" +repoName,
                                repositoryWebURL
                        )
                ) {

                    MethodTracker methodTracker = CodeTracker
                            .methodTracker()
                            .repository(repository)
                            .filePath(filePath)
                            .startCommitId(commitId)
                            .methodName(methodName)
                            .methodDeclarationLineNumber(lineNumber)
                            .build();

                    History<Method> methodHistory = methodTracker.track();

                    List<History.HistoryInfo<Method>> methodHistoryInfo = methodHistory.getHistoryInfoList();
                    History.HistoryInfo<Method> firstChange = methodHistoryInfo.get(0);
                    List<Map<String, String>> originalChanges = expectedChanges
                            .stream()
                            .filter(historyBlock -> historyBlock.get("commitId").equals(firstChange.getCommitId()))
                            .collect(Collectors.toList());
                    if (originalChanges.size() == 0 || !originalChanges.get(0).get("changeType").equals("introduced") || expectedChanges.size() != methodHistoryInfo.size()) {
                            createOracleEntry(
                                    repository,
                                    repoName,
                                    repositoryWebURL,
                                    filePath,
                                    methodJSON,
                                    commitId,
                                    methodHistoryInfo,
                                    oracleType,
                                    file.getName()
                            );
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
            Map<String, Object> methodJSON,
            String commitId,
            List<History.HistoryInfo<Method>> methodHistoryInfo,
            String oracleType,
            String oracleFilePath
    ) {
        String changes = null;
        ArrayList<REST.CTHBlockOracleComment> changeLog = new ArrayList<REST.CTHBlockOracleComment>();
        IRepository gitRepository = new GitRepository(repository);

        for (History.HistoryInfo<Method> historyInfo : methodHistoryInfo) {
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
                        "\"expectedChanges\": " +
                        changes +
                        "}";

        try {
            // Creates a Writer using FileWriter
            String fileName =
                    "src/main/resources/oracle/new-method/" +
                            oracleType +
                            "/false/" +
                            oracleFilePath;


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
