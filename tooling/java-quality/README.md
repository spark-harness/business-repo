# Java Quality

`java-quality` 负责 business-repo 手写 Java 项目的变更选择和 Maven 质量门禁执行。

## 计划

```bash
python3 tooling/java-quality/java_quality.py plan packages/java/money/pom.xml
python3 tooling/java-quality/java_quality.py plan-git --base-ref origin/master --output /tmp/java-quality-plan.json
```

输出包含：

- `JAVA_PROJECT_SELECT <project>`：本次选中的 Java 项目。
- `JAVA_PROJECT_LAYER <n> <projects>`：可以并行执行的项目层。
- `JAVA_PROJECT_SKIP no_java_project_changes`：本次没有 Java 项目变更。
- `JAVA_PROJECT_FAIL <project>`：路径或项目失败。

## 执行

```bash
python3 tooling/java-quality/java_quality.py run-project money
python3 tooling/java-quality/java_quality.py run-project spring-starter --plan /tmp/java-quality-plan.json --skip-unselected
```

每个项目执行 Spotless、Checkstyle、单元测试和 SpotBugs。`spring-starter` 成功后会执行 `mvn install`，供 `applicant-api` 消费同分支 snapshot。
