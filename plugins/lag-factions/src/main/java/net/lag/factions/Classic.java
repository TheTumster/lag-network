package net.lag.factions;

/** Classic factions chat look: gold "_____.[ title ]._____" headers, gold labels, yellow values. */
final class Classic {
	static final int WIDTH = 49; // characters of "_" a full header spans (matches the /f map)

	private Classic() { }

	/** Pixel advance in the default font (glyph + 1px). */
	static int px(char c) {
		if (c == ' ') return 4;
		if ("i!.,:;|'".indexOf(c) >= 0) return 2;
		if ("l`".indexOf(c) >= 0) return 3;
		if ("It[]".indexOf(c) >= 0) return 4;
		if ("fk<>(){}\"*".indexOf(c) >= 0) return 5;
		if (c == '@' || c == '~') return 7;
		return 6;
	}

	static int px(String plain) {
		int w = 0;
		for (char c : plain.toCharArray()) w += px(c);
		return w;
	}

	/**
	 * "____________.[ (x,z) laG ].____________" in gold. titleMm is MiniMessage, titlePlain the same text without tags
	 * (used to measure it so the header lines up with a WIDTH-wide row of "-").
	 */
	static String header(String titleMm, String titlePlain) {
		int total = WIDTH * 6;
		int mid = px(".[ " + titlePlain + " ].");
		int side = Math.max(2, (total - mid) / 2 / 6);
		String u = "_".repeat(side);
		return "<gold>" + u + ".[ </gold>" + titleMm + "<gold> ]." + u + "</gold>";
	}

	static String line(String label, String valueMm) {
		return "<gold>" + label + ": </gold><yellow>" + valueMm + "</yellow>";
	}
}
