
To run the API: 
`$env:GITHUB_OAUTH = '%key_here%'`

`mvn clean compile exec:java -D exec.mainClass="org.CodeTrackerAPI.REST"`

Demo URL:
`http://localhost:5000/api/track?owner=checkstyle&repoName=checkstyle&filePath=src/main/java/com/puppycrawl/tools/checkstyle/Checker.java&commitId=119fd4fb33bef9f5c66fc950396669af842c21a3&selection=fireErrors&lineNumber=384`