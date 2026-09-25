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

### Tech Stack

* Java 21 - Main programming language used.
* Gradle - Build tool used.
* [Quarkus](https://quarkus.io/) - A "supersonic" Java development framework for the cloud era, natively supporting AI,
  Docker, K8s, Native Image (native binary machine code compilation, de-JVM-ification), with efficiency far exceeding
  Spring Boot in cloud deployment, cold start, performance, and memory footprint.
    * **quarkus-langchain4j-openai** - Quarkus extension for integrating AI, supporting declarative usage and highly
      configurable.
    * **quarkus-reactive-pg-client** - Quarkus database driver extension, used with PostgreSQL.
    * **quarkus-hibernate-reactive-panache** - ORM framework. Quarkus's exclusive Panache pattern, making reactive
      database operations as simple and efficient as writing Active Record.
    * **quarkus-resteasy-reactive-jackson** - Core Gateway/Router. High-performance reactive HTTP service based on
      Vert.x, supporting SSE (Server-Sent Events) for AI text streaming typewriter output.
    * **quarkus-websockets-next** - Bidirectional Communication. Used for voice streaming transmission or real-time
      notification push when strong alerts are triggered.
    * **quarkus-smallrye-jwt** - Security Authentication. Lightweight decentralized user authentication based on JWT.
    * **quarkus-redis-client** - Cache & Rate Limiting. Used for temporary storage of context, high-frequency word cloud
      caching, and token bucket rate limiting to prevent interfaces from being maliciously flooded.
* [Jackson](https://github.com/FasterXML/jackson) - Efficient serialization library based on compile-time processing.
* [Netty](https://netty.io/) - Efficient asynchronous network I/O library.
* [SLF4J](https://www.slf4j.org/) - Unified logging facade library.
* [JetBrains Annotations](https://github.com/JetBrains/java-annotations) - Auxiliary annotation library for uniformly
  marking data flow direction/static analysis markers in this project.
* [PostgreSQL](https://www.postgresql.org/) - Core database used. Compared with traditional MySQL, it offers stronger
  efficiency and performance.
* Redis - Database for caching and rate limiting.
* ~~Docker~~ - Because containerized deployment is achievable and there are environment images for "audio → text"
  processing, it has been taken into consideration. ***If used, PostgreSQL will also be used as the form of Docker
  Image.***

---

## Backend Architecture

```text
├── src/main/java/kurvcygnus/soulnotes/
│   ├── config/
│   ├── utils/
│   ├── exception/
│   │
│   ├── domain/
│   │   ├── auth/
│   │   ├── diary/
│   │   │   ├── entity/
│   │   │   ├── resource/
│   │   │   └── service/
│   │   ├── chat/
│   │   └── voice/
│   │
│   ├── ai/
│   │   ├── agent/
│   │   ├── tool/
│   │   └── retriever/
│   │
│   └── websocket/
└── src/main/resources/
    └── application.properties
```

---

## Code Styles

This project does not use a formatter. Follow this code style when writing and enforce it during review:

* Abstract class should always use `Abstract` as prefix.
* All interfaces must use `I` as a prefix.
* Prefer sealed inheritance over open inheritance. `Impl` or `Implementation` must not appear in any filename.
* Strict Allman-Style is required.\
  Allman governs brace placement, not vertical space: single-statement bodies (delegating constructors, compact one-liners, guard throws) should stay on a single line as `{ ... }` — e.g. `private PrintUtils() { throw new IllegalAccessError("..."); }` or `public Foo(Bar bar) { this.bar = bar; }` are idiomatic; expanding such bodies to multiple lines is over-application of the style.\
  Example:
  ```java
  public final class Main extends IFoo
  {
      private static final Logger = PrintUtils.getLogger();
      
      //* Always mark [[NotNull]] and [[Nullable]] on params, fields, for local variables, [[Nullable]] is a must but [[NotNull]] does not.
      public static void main(@NotNull String... args)
      {
          //* For single line statements, covering the scope with `{}` is not mandatory.
          //* If `{}` is used at such a case, you should write it like this: `for(...) { ... }`
          for(final var arg: args)//* Using `final` in local varaibles(method params is not included) is recommend.
              System.out.println(PrintUtils.quickFormat("Arg \"{}\" got.\n", arg));//* When producing formatted strings, always use [[PrintUtils#quickFormat]].
          
          SomeClass.run(args);
      }
  }
  
  //* When a method's signature is too long, split it like this.
  private void bar(
      @NotNull IFoo foo,
      @NotNull List<Bar> bars,
      @Nullable Consumer<Bar> callback
  ) throws NullPointerException
  {
      //* Always do non-null assertions for [[NotNull]] params unless the source is completely reliable, just like `Main#main`'s param, `args`.
      Objects.requireNonNull(foo, "Param \"foo\" must not be null!");
      Objects.requireNonNull(bars, "Param \"bars\" must not be null!");
  
      //* Always use guard clauses.
      if(callback == null)
          return;
      
      bars.stream().
          filter(foo.bar()::baz).//* Method Reference is perfered rather than Lambda.
          forEach(callback);
  }
  
  //* Also, you should follow the usage of `[[${Ref}]]` from comments above, it can be actually a reference when some plugin is installed, `${Ref}` can be either file reference or class reference.
  ```
* About `var`: Use it only when the type is non-primitive and obvious from the right-hand side expression (e.g.,
  `var list = new ArrayList<String>()` is OK; `var result = compute()` is not).
* JetBrains Annotations are the highest-authority lint annotations for this project. Other annotation libraries may be
  used as long as they do not duplicate JetBrains equivalents.
