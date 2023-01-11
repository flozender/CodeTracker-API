package org.CodeTrackerAPI;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.uom.java.xmi.*;
import gr.uom.java.xmi.decomposition.AbstractCodeFragment;
import gr.uom.java.xmi.decomposition.CompositeStatementObject;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.codetracker.BaseTracker;
import org.codetracker.api.CodeElement;
import org.codetracker.element.Attribute;
import org.codetracker.element.Method;
import org.codetracker.util.CodeElementLocator;
import org.codetracker.util.GitRepository;
import org.eclipse.jgit.lib.Repository;
import org.refactoringminer.api.GitService;
import org.refactoringminer.util.GitServiceImpl;
import org.w3c.dom.Attr;

public class AttributeValidator {

    public static void main(String[] args) {
        String oracleType = "test";
        File folder = new File("src/main/resources/oracle/method/" + oracleType);
        File[] listOfFiles = folder.listFiles();

        File trueFolder = new File(
                "src/main/resources/oracle/attribute/" + oracleType + "/true"
        );
        try {
            trueFolder.mkdirs();
        } catch (Exception e) {
            System.err.println("Failed to create True directory");
        }

        HashSet<String> attributesInOperations = new HashSet<>();

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
                        new TypeReference<Map<String, Object>>() {
                        }
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

                    GitRepository iRepository = new GitRepository(repository);
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
                                Attribute attr = Attribute.of(
                                        attribute,
                                        iRepository.getVersion(commitId)
                                );
                                attributesInOperations.add(attr.getIdentifier());
                            }
                        }
                    }
                    System.out.println("Valid attributes are now");
                    System.out.println(attributesInOperations);
                }
            } catch (Exception e) {
                System.out.println(e);
            }
        }

        File attributesFolder = new File(
                "src/main/resources/oracle/attribute/" + oracleType
        );
        File[] listOfAttributeFiles = attributesFolder.listFiles();

        assert listOfAttributeFiles != null;
        for (File file : listOfAttributeFiles) {
            if (!file.isFile()) {
                continue;
            }

            System.out.println("Attribute FILE IS " + file);
            ObjectMapper mapper = new ObjectMapper();
            try {
                String data = FileUtils.readFileToString(file, "UTF-8");

                Map<String, Object> attributeJSON = mapper.readValue(
                        data,
                        new TypeReference<Map<String, Object>>() {
                        }
                );

                String repoName = (String) attributeJSON.get("repositoryName");
                String repositoryWebURL = (String) attributeJSON.get(
                        "repositoryWebURL"
                );
                String commitId = (String) attributeJSON.get("startCommitId");
                String filePath = (String) attributeJSON.get("filePath");
                String attributeName = (String) attributeJSON.get("attributeName");
                Integer lineNumber = (Integer) attributeJSON.get(
                        "attributeDeclarationLine"
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
                            attributeName,
                            lineNumber
                    );
                    CodeElement codeElement = locator.locate();

                    if (codeElement == null) {
                        throw new Exception("Selected code element is invalid.");
                    }
                    Attribute attribute = (Attribute) codeElement;
                    try {
                        String newFolderName =
                                "src/main/resources/oracle/attribute/" + oracleType + "/false/";
                        if (attributesInOperations.contains(attribute.getIdentifier())) {
                            newFolderName =
                                    "src/main/resources/oracle/attribute/" + oracleType + "/true/";
                        }
                        String fileName = file.getName();

                        file.renameTo(new File(newFolderName + fileName));
                    } catch (Exception e) {
                        e.getStackTrace();
                        System.out.println("Something went wrong: " + e);
                    }
                }
            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }
}
