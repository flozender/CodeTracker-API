package org.CodeTrackerAPI;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.uom.java.xmi.LocationInfo.CodeElementType;
import org.CodeTrackerAPI.REST.CTHBlockOracle;
import org.CodeTrackerAPI.REST.CTHBlockOracleComment;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.codetracker.api.BlockTrackerGumTree;
import org.codetracker.api.CodeTracker;
import org.codetracker.api.History;
import org.codetracker.api.History.HistoryInfo;
import org.codetracker.change.Change;
import org.codetracker.element.Block;
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

public class BlockOracleGenerator {

    public static String oracleType = "training";
    public static HashMap<String, Integer> methodBlocks = new HashMap<>();

    public static void main(String[] args) {
        System.out.println("Generating GumTree Block Oracle...");
        try {
            // Reset log file
            File logFile = new File("src/main/resources/oracle/gumtree/log.txt");
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
                "src/main/resources/oracle/gumtree/" + oracleType + "/true"
        );
        File falseFolder = new File(
                "src/main/resources/oracle/gumtree/" + oracleType + "/false"
        );
        File validFolder = new File(
                "src/main/resources/oracle/gumtree/" + oracleType + "/valid"
        );
        File invalidFolder = new File(
                "src/main/resources/oracle/gumtree/" + oracleType + "/invalid"
        );
        File invalidReportedFolder = new File(
                "src/main/resources/oracle/gumtree/" + oracleType + "/invalid/reported"
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
                        new TypeReference<Map<String, Object>>() {
                        }
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
                        new TypeReference<Map<String, Object>>() {
                        }
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
                        new TypeReference<Map<String, Object>>() {
                        }
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
                    new File("src/main/resources/oracle/gumtree/" + oracleType)
            );
            trueFolder.mkdirs();
            falseFolder.mkdirs();
            validFolder.mkdirs();
            invalidFolder.mkdirs();
            invalidReportedFolder.mkdirs();
        } catch (Exception e) {
            handleError(e, 4);
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

                Map<String, Object> blockJSON = mapper.readValue(
                        data,
                        new TypeReference<Map<String, Object>>() {
                        }
                );

                String repoName = (String) blockJSON.get("repositoryName");
                String repoOwner = blockJSON.get("repositoryWebURL").toString().split("/")[3];
                String repositoryWebURL = (String) blockJSON.get("repositoryWebURL");
                String commitId = (String) blockJSON.get("startCommitId");
                String filePath = (String) blockJSON.get("filePath");
                String methodName = (String) blockJSON.get("functionName");
                Integer methodLineNumber = (Integer) blockJSON.get("functionStartLine");
                String blockType = (String) blockJSON.get("blockType");
                Integer blockStartLine = (Integer) blockJSON.get("blockStartLine");
                Integer blockEndLine = (Integer) blockJSON.get("blockEndLine");

                ArrayList<Map<String, String>> expectedChanges = (ArrayList<Map<String, String>>) blockJSON.get(
                        "expectedChanges"
                );

                GitService gitService = new GitServiceImpl();

                try (
                        Repository repository = gitService.cloneIfNotExists(
                                "tmp/" +repoOwner+"/" +repoName,
                                repositoryWebURL
                        )
                ) {

                    BlockTrackerGumTree blockTracker = CodeTracker
                            .blockTrackerGumTree()
                            .repository(repository)
                            .filePath(filePath)
                            .startCommitId(commitId)
                            .methodName(methodName)
                            .methodDeclarationLineNumber(
                                    methodLineNumber
                            )
                            .codeElementType(
                                    CodeElementType.valueOf(blockType)
                            )
                            .blockStartLineNumber(
                                    blockStartLine
                            )
                            .blockEndLineNumber(
                                    blockEndLine
                            )
                            .build();

                    History<Block> blockHistory = blockTracker.track();
                    List<HistoryInfo<Block>> blockHistoryInfo = blockHistory.getHistoryInfoList();

                    HistoryInfo<Block> firstChange = blockHistoryInfo.get(0);

                    List<String> originalChanges = expectedChanges
                            .stream()
                            .filter(historyBlock -> historyBlock.get("commitId").equals(firstChange.getCommitId()))
                            .map(historyBlock -> historyBlock.get("changeType"))
                            .collect(Collectors.toList());

                    if (originalChanges.size() == 0 || !originalChanges.get(0).equals("introduced")) {
                        createOracleEntry(
                                repository,
                                file,
                                blockJSON,
                                commitId,
                                blockHistoryInfo,
                                oracleType,
                                false,
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
            File file,
            Map<String, Object> blockJSON,
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

        StringBuilder originalChanges = new StringBuilder();
        ArrayList<Map<String, Object>> expectedChanges = (ArrayList<Map<String, Object>>) blockJSON.get(
                "expectedChanges"
        );
        originalChanges.append("[");
        for (Map<String, Object> change : expectedChanges) {
            originalChanges.append("{");
            for (Map.Entry<String, Object> entry : change.entrySet()){
                if (entry.getKey().equals("commitTime")){
                    originalChanges.append("\"").append(entry.getKey()).append("\": ").append(entry.getValue()).append(",");
                } else {
                    originalChanges.append("\"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\",");
                }
            }
            originalChanges.append("},");
        }
        originalChanges.deleteCharAt(originalChanges.length() - 1);
        originalChanges.append("]");

        String response =
                "{\"repositoryName\": \"" +
                        blockJSON.get("repositoryName") +
                        "\"," +
                        "\"repositoryWebURL\": \"" +
                        blockJSON.get("repositoryWebURL") +
                        "\"," +
                        "\"filePath\": \"" +
                        blockJSON.get("filePath") +
                        "\",";


        response =
                response +
                        "\"functionName\": \"" +
                        blockJSON.get("functionName") +
                        "\"," +
                        "\"functionKey\": \"" +
                        blockJSON.get("functionKey") +
                        "\"," +
                        "\"functionStartLine\": " +
                        blockJSON.get("functionStartLine") +
                        "," +
                        "\"blockType\": \"" +
                        blockJSON.get("blockType") +
                        "\"," +
                        "\"blockKey\": \"" +
                        blockJSON.get("blockKey") +
                        "\"," +
                        "\"blockStartLine\": " +
                        blockJSON.get("blockStartLine") +
                        "," +
                        "\"blockEndLine\": " +
                        blockJSON.get("blockEndLine") +
                        "," +
                        "\"startCommitId\": \"" +
                        commitId +
                        "\"," +
                        "\"expectedChanges\": " +
                        changes +
                        "," +
                        "\"oracleChanges\": " +
                        originalChanges +
                        "}";

        String folderName = valid ? "true" : "false";

        String fileKey = commitId + "-" + blockJSON.get("blockType");
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
            String className = blockJSON.get("filePath").toString()
                    .split("/")[blockJSON.get("filePath").toString()
                    .split("/")
                    .length -
                    1].split("\\.")[0];
            String fileName =
                    "src/main/resources/oracle/gumtree/" +
                            oracleType +
                            "/" +
                            folderName +
                            "/" +
                            blockJSON.get("repositoryName") +
                            "-" +
                            className +
                            "-" +
                            blockJSON.get("functionName") +
                            "-" +
                            blockJSON.get("blockType");

            String absoluteFileName =
                    blockJSON.get("repositoryName") +
                            "-" +
                            className +
                            "-" +
                            blockJSON.get("functionName") +
                            "-" +
                            blockJSON.get("blockType");
            // check if file already exists, add numerals at the end if true
//            if (methodBlocks.containsKey(absoluteFileName)) {
//                log(
//                        "Key contained: " +
//                                absoluteFileName +
//                                " -> " +
//                                methodBlocks.get(absoluteFileName)
//                );
//                fileName =
//                        fileName +
//                                "-" +
//                                methodBlocks.get(absoluteFileName).toString() +
//                                ".json";
//                methodBlocks.put(
//                        absoluteFileName,
//                        methodBlocks.get(absoluteFileName) + 1
//                );
//            } else {
//                log("No key yet: " + absoluteFileName);
//                fileName = fileName + ".json";
//                methodBlocks.put(absoluteFileName, 1);
//            }

            FileWriter output = new FileWriter(file.getName());

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
            File logFile = new File("src/main/resources/oracle/gumtree/log.txt");
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
        log("Case " + number + " - Failed: " + e);
        log(stacktrace);
    }
}
