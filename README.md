Clone and build the latest versions of [Refactoring Miner](https://github.com/tsantalis/RefactoringMiner) and [code-tracker/coping-with-RMiner-changes](https://github.com/jodavimehran/code-tracker/tree/coping-with-RMiner-changes)

# Instructions
> Clone this branch
> https://github.com/jodavimehran/code-tracker/tree/coping-with-RMiner-changes

> Then clone RefactoringMiner latest commit from master.
> Change in the `pom.xml` the version to `2.3.2-SNAPSHOT`
> and run
> `gradle publishToMavenLocal`

> Then go to code-tracker and make it depend on
> `2.3.2-SNAPSHOT`

> Then build code-tracker by running `mvn clean install -U -o`

> CodeTracker API should be working with this build.

(credits [@tsantalis](https://github.com/tsantalis) Nikolaos Tsantalis)

To run the API: 
 - `mvn clean install -U -o`
 - `mvn clean compile exec:java -D exec.mainClass="org.CodeTrackerAPI.REST"`

To provide GitHub credentials for tracking private repositories, set environment variables `GITHUB_USERNAME` and `GITHUB_KEY` before running the API.

Demo URL:
`http://localhost:5000/api/track?owner=checkstyle&repoName=checkstyle&filePath=src/main/java/com/puppycrawl/tools/checkstyle/Checker.java&commitId=119fd4fb33bef9f5c66fc950396669af842c21a3&selection=fireErrors&lineNumber=384`
