
To run the API: 
`$env:GITHUB_OAUTH = '%key_here%'`

`mvn clean compile exec:java -D exec.mainClass="org.CodeTrackerAPI.REST"`

Demo URL:
`http://localhost:8080/api/method?owner=checkstyle&repoName=checkstyle&filePath=src/main/java/com/puppycrawl/tools/checkstyle/Checker.java&commitId=119fd4fb33bef9f5c66fc950396669af842c21a3&methodName=fireErrors&lineNumber=384`

Known Issues:
 - Does not replace "master" commitId with the actual commit ID
 - Implemented only for "method"
