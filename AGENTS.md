# Soul Notes

## Project Context & Core Business Logic

The project you are working on is **"Soul Notes"**, a multimodal AI and sentiment-analysis-based psychological light-intervention system for university students.

When analyzing, reviewing, or generating code, you **must** strictly align with the following core features and
technical requirements of the system:

1. **Multimodal Input Handling**: The system ingests both Voice and Text. Voice is converted to text before entering the LLM pipeline (`Voice -> Text -> LLM Parsing -> Sentiment Analysis`). **Ensure data structures support this pipeline.**
2. **Sentiment Analysis & Visualization**: The backend must process real-time emotional valence and anxiety values to generate data for the frontend "Emotion Weather Forecast" visualization.
3. **Empathetic & Non-Medicalized Response Style**: Any prompt engineering or text generation logic within the system must position the AI as a "psychological listener" — warm, non-judgmental, and strictly avoiding medicalized labels.
4. **High-Risk Alert (Red Alert Mechanism)**:
    * **Online**: If self-harm or severe tendencies are detected via sentiment analysis, the system must trigger an immediate popup with the psychological center hotline.
    * **Offline Safety Net (Crucial)**: The architecture must include an offline safety mechanism. If AI services or networks fail, a local fallback mechanism must guarantee the display of the emergency hotline.

---

## Universal Principles

* **Dependency Policy**: Use only existing dependencies. Introducing new dependencies or updating existing ones is forbidden. If a new dependency would clearly benefit the project and the developer asks about it, you may explain its value — but **do not add it**. The developer must coordinate with the team before any dependency change.
* **Comment Conventions**: Use `//*` for critical information, `//!` for potential errors or edge cases, and `//?` for TODOs or build/config explanations. Comments must explain **why**, not **what**. All comments must be written in **Chinese**.

   ```java
   //  e.g.
   //* This is an important comment!
   //! This is a comment that explains potential errors.
   //? This is a comment that records your confusion, TODO, or the explanation in build script and config.
   ```

* **Zero Warnings**: **Zero warnings is our goal.** For unavoidable API issues, suppress with `@SuppressWarnings` and include a `//! Reason...` comment. **Deprecated APIs must never be used**, regardless of circumstances.
   ```java
   @SuppressWarnings("ConstantConditions")//! Reason with explanation...
   Foo.bar(null);
   ```

* **Code Structure**: Use `//region` / `//endregion` to organize code into logical sections. Skip this only when the file is simple and straightforward.
* **MCP Check**: Check whether MCP `intellij` and `codegraph` is enabled/exists, if so, using these MCP Tool (`intellij` first, it also supports dependency source code query, detailed lint information and quick compile & build tests) to lookup code-related information as default.

  ```text
  //region Section of this region
  // Fields, constructors, methods, etc.
  //endregion
  ```

* **Character Usage**: Always use half-width characters (`.`, `,`, `()`) instead of full-width characters (`。`, `，`, `（）`) in all output. Full-width characters are not international and break monospace fonts.

---

### CRITICAL PROTOCOL: SKILL ENFORCEMENT & HARD STOP

**CRITICAL INSTRUCTION — READ BEFORE ANY TASK EXECUTION:**
You operate under a STRICT, ZERO-TOLERANCE rule regarding Project Skills (`skill(...)`). Failure to obey this protocol
is a SYSTEM FAILURE.

1. **MANDATORY SKILL PRE-CHECK (FIRST STEP):**
    - BEFORE analyzing code, modifying files, generating solutions, or writing ANY text, you MUST check if your project
      environment contains available skills.
    - If ANY available skill description matches, intersects with, or relates to the current user request/goal, **YOU
      ARE EXPRESSLY FORBIDDEN FROM SOLVING THE TASK DIRECTLY.**
    - You MUST IMMEDIATELY and EXCLUSIVELY issue the command: `skill(${skill_name})` and STOP.

2. **HARD STOP ON MISSING SKILL ACCESS (CIRCUIT BREAKER):**
    - If you cannot execute or access skills (e.g. running in an environment without skill execution privileges), **YOU
      MUST INSTANTLY HALT ALL WORK.**
    - DO NOT generate code. DO NOT propose fixes. DO NOT offer step-by-step guides or alternative solutions.
    - Output EXACTLY and ONLY the following response (verbatim):
      > I can't access skills in this project, if this session requires any skills to use, please specify it manually.

3. **STRICT ENFORCEMENT & FORBIDDEN ACTIONS:**
    - Proceeding to answer, write code, or execute a task WITHOUT calling an applicable skill or trigger-checking skills
      is a SEVERE VIOLATION.
    - Treat this rule as a BLOCKING COMPILATION ERROR.

---

## Further Instructions & Context Routing

You MUST determine the specific task type (Writing, Refactoring, Reviewing, or Guidance) based on the user's request before executing any action or generating code.\
If the specific contextual guidelines are missing from the session history, you must actively request or read them using the following protocols:

1. **If you detect the user wants to WRITE NEW CODE (including Unit Tests):**
    * IMMEDIATELY stop generating code.
    * Output this exact text to request the file:
      > `[Missing Context] Please provide or read the contents of docs/backend/DEVELOPMENT_GUIDELINES.md to proceed with writing code.`
    * (If your platform supports file-reading tools/slash commands, execute `/read docs/backend/DEVELOPMENT_GUIDELINES.md` or `@docs/backend/DEVELOPMENT_GUIDELINES.md` immediately).
    * When file is read, response: "OK. Now we will focus on Developing."

2. **If you detect the user wants to REFACTOR CODE:**
    * IMMEDIATELY stop generating code.
    * Output this exact text to request the file:
      > `[Missing Context] Please provide or read the contents of docs/backend/REFACTOR_RULES.md to proceed with refactoring code.`
    * (If your platform supports file-reading tools/slash commands, execute `/read docs/backend/REFACTOR_RULES.md` or `@docs/backend/REFACTOR_RULES.md` immediately).
   * When file is read, response: "OK. Now we will focus on Refactoring."

3. **If you detect the user wants to REVIEW CODE:**
    * IMMEDIATELY stop generating code.
    * Output this exact text to request the file:
      > `[Missing Context] Please provide or read the contents of docs/backend/REVIEW_PRINCIPLES.md to proceed with code review.`
    * (If your platform supports file-reading tools/slash commands, execute `/read docs/backend/REVIEW_PRINCIPLES.md` or `@docs/backend/REVIEW_PRINCIPLES.md` immediately).
    * When file is read, response: "OK. Now we will focus on Reviewing."

4. **If you detect the user is ASKING FOR GUIDANCE (e.g., conceptual questions, explaining existing code):**
    * Focus ENTIRELY on teaching and explaining.
    * Do NOT edit, rewrite, or generate any workspace files.
    * Rely on the architectural patterns defined in this `BASICS.md` to guide them.
    * When file is read, response: "OK. Now we will focus on Learning. No File Edits will be invoked."

5. **Strict Enforcement:** Do not attempt to guess, assume, or hallucinate rules for writing, refactoring, or reviewing. If the corresponding task-specific `.md` document has not been explicitly pasted into the chat history, attached to the session, or indexed by your system, you MUST invoke step 1, 2, or 3.
