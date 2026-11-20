# app-tests failure：run 44752462844 sha 00a6ee5882fa297088211b4830600d022df9c0d9
```
    java.lang.AssertionError at MavenArtifactFetcher.java:130
        Caused by: java.util.concurrent.ExecutionException at AbstractFuture.java:168
            Caused by: java.io.IOException at HttpURLConnection.java:2034
BackNavigationTest > classMethod FAILED
    java.lang.AssertionError at MavenArtifactFetcher.java:130
        Caused by: java.util.concurrent.ExecutionException at AbstractFuture.java:168
            Caused by: java.io.IOException at HttpURLConnection.java:2034
> Task :app:testDebugUnitTest FAILED
FAILURE: Build failed with an exception.
Caused by: org.gradle.api.internal.exceptions.MarkedVerificationException: There were failing tests. See the report at: file:///ho
```
ep.lambda$execute$2(ExecuteWorkBuildOperationFiringStep.java:67)
	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:67)
	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:39)
	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:46)
	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:34)
	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:56)
	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:38)
	at org.gradle.internal.execution.impl.DefaultExecutionEngine$1.execute(DefaultExecutionEngine.java:68)
	at org.gradle.api.internal.tasks.execution.DefaultTaskNodeExecutor.doExecute(DefaultTaskNodeExecutor.java:364)
	... 25 more


Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/9.8.2/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 1m 54s
43 actionable tasks: 25 executed, 18 from cache
Configuration cache entry stored.
