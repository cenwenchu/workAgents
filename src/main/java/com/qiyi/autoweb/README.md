# AutoWeb Agent

This module provides an autonomous agent that can execute web automation tasks based on natural language instructions.

## Components

- **AutoWebAgent.java**: The main entry point. It orchestrates the process of connecting to the browser, fetching HTML, querying the LLM, compiling the generated code, and executing it.
- **HTMLCleaner.java**: A utility to clean and simplify HTML content to reduce token usage and improve LLM performance.
- **WebAction.java**: The interface that the dynamically generated code implements.

## Usage

You can run the agent using Maven:

```bash
mvn exec:java -Dexec.mainClass="com.qiyi.autoweb.AutoWebAgent" -Dexec.args="<TARGET_URL> <USER_PROMPT>"
```

### Example

```bash
mvn exec:java -Dexec.mainClass="com.qiyi.autoweb.AutoWebAgent" -Dexec.args="https://sc.scm121.com/tradeManage/tower/distribute 'Find the first order and click audit'"
```

If no arguments are provided, it defaults to the example requested:
- URL: `https://sc.scm121.com/tradeManage/tower/distribute`
- Prompt: `执行查询待发货的订单，选中其中第一条，点击审核推单`

## Requirements

- A running Chrome instance with remote debugging enabled on port 9222 (default).
  - Command: `chrome --remote-debugging-port=9222 --user-data-dir="${HOME}/chrome-debug-profile"`
- Valid API keys configured in `src/main/resources/podcast.cfg` (specifically for DeepSeek or the configured LLM).
