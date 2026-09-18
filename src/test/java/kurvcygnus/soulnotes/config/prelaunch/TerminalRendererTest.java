package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerminalRendererTest
{
    //* JetBrains 注解为 compileOnly 依赖, 不在 test 编译类路径上, 故测试源不使用 (与既有测试保持一致).
    //* ANSI 转义序列以实现常量为断言基准 (brief 授权): 逐字面量锁定, 防视觉规范漂移.

    @Test void paintWrapsAnsiCodes()
    {
        //* Visual Companion v2 色板 → 标准 SGR 映射, 六个有色样式两两互异:
        //* DIM 灰斜体(#8b949e) / EMPHASIS 黄粗斜体(#e3b341) / OK 绿(#3fb950) / BAD 红(#f85149) / OPT 天蓝(#58a6ff) / VALUE 浅蓝(#a5d6ff).
        assertEquals("\u001b[2;3;90m x \u001b[0m", TerminalRenderer.paint(TerminalRenderer.Style.DIM, " x "));
        assertEquals("\u001b[1;3;33m强调\u001b[0m", TerminalRenderer.paint(TerminalRenderer.Style.EMPHASIS, "强调"));
        assertEquals("\u001b[32m已配\u001b[0m", TerminalRenderer.paint(TerminalRenderer.Style.OK, "已配"));
        assertEquals("\u001b[31m失败\u001b[0m", TerminalRenderer.paint(TerminalRenderer.Style.BAD, "失败"));
        assertEquals("\u001b[94m可选\u001b[0m", TerminalRenderer.paint(TerminalRenderer.Style.OPT, "可选"));
        assertEquals("\u001b[96m取值\u001b[0m", TerminalRenderer.paint(TerminalRenderer.Style.VALUE, "取值"));
    }

    @Test void resetPaintIsIdentity()
    {
        //* RESET 是复位常量而非颜色: paint(RESET) 恒等返回, 供需要裸文本的调用方使用.
        assertEquals("原样文本", TerminalRenderer.paint(TerminalRenderer.Style.RESET, "原样文本"));
    }

    @Test void listItemLineRendersRequiredEmptyState()
    {
        //* 必填未填: 白色空心圆 (默认前景, 无转义) + 变量名 + 双空格 + 灰斜体中文名, 无行尾回显.
        assertEquals(
            "○ SOULNOTES_JWT_SECRET  \u001b[2;3;90mJWT 签名密钥\u001b[0m",
            TerminalRenderer.listItemLine(new TerminalRenderer.ListItem(
                TerminalRenderer.ItemState.REQUIRED_EMPTY, "SOULNOTES_JWT_SECRET", "JWT 签名密钥", null))
        );
    }

    @Test void listItemLineRendersConfiguredStateWithMask()
    {
        //* 必填已配: 绿色实心圆 + 行尾浅蓝回显; 密钥掩码由调用方以 "******" 传入, 渲染器只负责格式.
        assertEquals(
            "\u001b[32m●\u001b[0m SOULNOTES_JWT_SECRET  \u001b[2;3;90mJWT 签名密钥\u001b[0m  \u001b[96m******\u001b[0m",
            TerminalRenderer.listItemLine(new TerminalRenderer.ListItem(
                TerminalRenderer.ItemState.CONFIGURED, "SOULNOTES_JWT_SECRET", "JWT 签名密钥", "******"))
        );
    }

    @Test void listItemLineRendersInvalidAsWholeLineRed()
    {
        //* 校验未通过: 整行红 (含圆点/变量名/中文名/失败原因), 无内层着色 — 内层复位会截断整行红色.
        final var line = TerminalRenderer.listItemLine(new TerminalRenderer.ListItem(
            TerminalRenderer.ItemState.REQUIRED_INVALID, "SOULNOTES_DB_URL", "数据库地址", "URL 必须以 postgresql:// 开头"));
        assertTrue(line.startsWith("\u001b[31m"), "整行必须以红色样式开头: " + line);
        assertTrue(line.endsWith("\u001b[0m"), "整行必须以复位结尾: " + line);
        assertTrue(line.contains("○ SOULNOTES_DB_URL"), "必须含红色空心圆与变量名: " + line);
        assertTrue(line.contains("数据库地址"), "中文名随整行变红: " + line);
        assertTrue(line.contains("URL 必须以 postgresql:// 开头"), "行尾回显失败原因摘要: " + line);
    }

    @Test void listItemLineRendersOptionalDefaultState()
    {
        //* 可选默认: 天蓝空心圆 + 行尾灰斜体 "默认 " 前缀回显.
        assertEquals(
            "\u001b[94m○\u001b[0m SOULNOTES_JWT_TTL  \u001b[2;3;90mJWT 有效期\u001b[0m  \u001b[2;3;90m默认 604800 (7 天)\u001b[0m",
            TerminalRenderer.listItemLine(new TerminalRenderer.ListItem(
                TerminalRenderer.ItemState.OPTIONAL_DEFAULT, "SOULNOTES_JWT_TTL", "JWT 有效期", "604800 (7 天)"))
        );
    }

    @Test void listItemLineRendersOptionalSetState()
    {
        //* 可选已填: 绿色实心圆 + 行尾浅蓝保存值.
        assertEquals(
            "\u001b[32m●\u001b[0m SOULNOTES_WEATHER_STORM  \u001b[2;3;90m暴雨阈值\u001b[0m  \u001b[96m0.9\u001b[0m",
            TerminalRenderer.listItemLine(new TerminalRenderer.ListItem(
                TerminalRenderer.ItemState.OPTIONAL_SET, "SOULNOTES_WEATHER_STORM", "暴雨阈值", "0.9"))
        );
    }

    @Test void eraseAboveBuildsCursorUpAndClearSequence()
    {
        //* 原地重写协议: 上移 n 行 → 逐行 (清行 + 下移) 抹掉旧内容 → 回块顶行首, 供调用方整体重写.
        assertEquals(
            "\u001b[2A\u001b[2K\u001b[1B\u001b[2K\u001b[1B\u001b[2A\r",
            TerminalRenderer.eraseAbove(2)
        );
    }

    @Test void eraseAboveIgnoresNonPositiveLines()
    {
        assertEquals("", TerminalRenderer.eraseAbove(0));
    }

    @Test void fakeTerminalScriptsInputs()
    {
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of("y", "n"), sink);
        io.writeOut("q1: ");
        assertEquals("y", io.readLine());
        io.writeErr("q2: ");
        assertEquals("n", io.readLine());
        assertTrue(sink.toString().contains("q1:"), "out 通道写入必须被捕获: " + sink);
        assertTrue(sink.toString().contains("q2:"), "err 通道写入必须被捕获: " + sink);
        assertNull(io.readLine(), "脚本耗尽 = EOF 语义, 返回 null");
    }

    @Test void fakeTerminalReturnsSecretsAsCharArray()
    {
        final var io = TerminalIO.fake(List.of("s3cr3t"), new StringBuilder());
        assertArrayEquals(new char[] {'s', '3', 'c', 'r', '3', 't'}, io.readSecret());
        assertNull(io.readSecret(), "脚本耗尽 = EOF 语义, 返回 null");
    }
}
