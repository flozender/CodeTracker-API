Clone and build the latest versions of [Refactoring Miner](https://github.com/tsantalis/RefactoringMiner) and [code-tracker](https://github.com/jodavimehran/code-tracker)

# Instructions
> Clone CodeTracker from master
 - `git clone https://github.com/jodavimehran/code-tracker`

> Then clone RefactoringMiner from master.
 - `git clone https://github.com/tsantalis/RefactoringMiner`
> Change the version to `3.0.1-SNAPSHOT` in `pom.xml` 
> and run
> `gradle publishToMavenLocal`

> Then go to CodeTracker and make it depend on
> `3.0.1-SNAPSHOT`

> Then build CodeTracker by running `mvn clean install -U -o`

> CodeTracker API should be working with this build.

(credits [@tsantalis](https://github.com/tsantalis) Nikolaos Tsantalis)

To run the API: 
 - `mvn clean install -U -o`
 - `mvn clean compile exec:java -D exec.mainClass="org.CodeTrackerAPI.REST"`

To provide GitHub credentials for tracking private repositories, set environment variables `GITHUB_USERNAME` and `GITHUB_KEY` before running the API.
 - `set GITHUB_USERNAME=<your_username>`
 - `set GITHUB_KEY=<your_github_key>`

Demo URL:
`http://localhost:5000/api/track?owner=checkstyle&repoName=checkstyle&filePath=src/main/java/com/puppycrawl/tools/checkstyle/Checker.java&commitId=119fd4fb33bef9f5c66fc950396669af842c21a3&selection=fireErrors&lineNumber=384`
