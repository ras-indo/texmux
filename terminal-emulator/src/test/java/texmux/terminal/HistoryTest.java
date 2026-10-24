package texmux.terminal;


public class HistoryTest extends TerminalTestCase {

	public void testHistory() {
		final int rows = 3;
		final int cols = 3;
		withTerminalSized(cols, rows).enterString("111222333444555666777888999");
		assertCursorAt(2, 2);
		assertLinesAre("777", "888", "999");
		assertHistoryStartsWith("666", "555");

		resize(cols, 2);
		assertHistoryStartsWith("777", "666", "555");

		resize(cols, 3);
		assertHistoryStartsWith("666", "555");
	}

	public void testHistoryWithScrollRegion() {
		// "CSI P_s ; P_s r" - set Scrolling Region [top;bottom] (default = full size of window) (DECSTBM).
		withTerminalSized(3, 4).enterString("111222333444");
		assertLinesAre("111", "222", "333", "444");
		enterString("\033[2;3r");
		// NOTE: "DECSTBM moves the cursor to column 1, line 1 of the page."
		assertCursorAt(0, 0);
		enterString("\nCDEFGH").assertLinesAre("111", "CDE", "FGH", "444");
		enterString("IJK").assertLinesAre("111", "FGH", "IJK", "444").assertHistoryStartsWith("CDE");
		enterString("LMN").assertLinesAre("111", "IJK", "LMN", "444").assertHistoryStartsWith("FGH", "CDE");
	}

	public void testScrollbackNoCorruptionAfterManyLines() {
		// Write enough lines to fill scrollback multiple times
		withTerminalSized(10, 5);
		for (int page = 0; page < 50; page++) {
			// Write one screenful of unique data
			for (int row = 0; row < mTerminal.mRows; row++) {
				enterString("line_" + page + "_" + row + "\n");
			}
		}

		// Scroll back to beginning
		String transcript = mTerminal.getScreen().getTranscriptText();

		// Verify first lines are intact
		assertTrue("Scrollback should contain first written line",
			transcript.contains("line_0_0"));
		assertTrue("Scrollback should contain last written line",
			transcript.contains("line_49_" + (mTerminal.mRows - 1)));

		// Verify no corruption (garbled text)
		// Each line should have the format "line_X_Y"
		String[] lines = transcript.split("\n");
		for (String line : lines) {
			if (line.startsWith("line_")) {
				String[] parts = line.split("_");
				assertEquals("Line should have 3 parts: " + line, 3, parts.length);
			}
		}
	}

}
