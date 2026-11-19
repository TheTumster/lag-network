package net.lag.skyblock;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * One look for every Skyblock menu: a black frame (outer ring) around a dark-gray panel, with accent-coloured
 * corners. Content sits on the panel, centred. Bright items on dark gray read clearly; the frame holds the buttons.
 */
final class Gui {
	private Gui() { }

	static ItemStack pane(Material mat) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		im.setHideTooltip(true);
		it.setItemMeta(im);
		return it;
	}

	/** Black frame, dark-gray panel inside, accent glass in the four corners. */
	static void frame(Inventory inv, Material accent) {
		int size = inv.getSize(), rows = size / 9;
		ItemStack edge = pane(Material.BLACK_STAINED_GLASS_PANE), panel = pane(Material.GRAY_STAINED_GLASS_PANE), corner = pane(accent);
		for (int i = 0; i < size; i++) {
			int r = i / 9, c = i % 9;
			boolean border = r == 0 || r == rows - 1 || c == 0 || c == 8;
			inv.setItem(i, border ? edge : panel);
		}
		inv.setItem(0, corner);
		inv.setItem(8, corner);
		inv.setItem(size - 9, corner);
		inv.setItem(size - 1, corner);
	}

	/**
	 * Slots for n items laid out centred on the panel (columns 1-7) from row `firstRow` down: full rows of 7, the
	 * last row centred. An even count skips the middle column so it still sits dead centre.
	 */
	static List<Integer> centred(int firstRow, int maxRows, int n) {
		List<Integer> out = new ArrayList<>();
		int row = firstRow;
		while (n > 0 && row < firstRow + maxRows) {
			int k = Math.min(7, n);
			for (int c : columns(k)) out.add(row * 9 + c);
			n -= k;
			row++;
		}
		return out;
	}

	/** The columns k items (1..7) take in a row, centred on column 4. */
	static int[] columns(int k) {
		return switch (k) {
			case 1 -> new int[] {4};
			case 2 -> new int[] {3, 5};
			case 3 -> new int[] {3, 4, 5};
			case 4 -> new int[] {2, 3, 5, 6};
			case 5 -> new int[] {2, 3, 4, 5, 6};
			case 6 -> new int[] {1, 2, 3, 5, 6, 7};
			default -> new int[] {1, 2, 3, 4, 5, 6, 7};
		};
	}

	// ---------------------------------------------------------------- titles

	/**
	 * Inventory titles draw dark text on the light-gray window, so bright gradients wash out there. Titles use one
	 * deep colour per menu (bold) with a mid-gray subtitle, padded with spaces so they sit centred over the window.
	 */
	static net.kyori.adventure.text.Component title(String main, String sub, String hex) {
		int w = width(main, true) + (sub == null ? 0 : width(" · " + sub, false));
		int room = 176 - 16; // window width minus the 8px margins
		int pad = Math.max(0, (room - w) / 2 / 4); // a space is 4px
		String text = " ".repeat(pad) + "<color:" + hex + "><bold>" + main + "</bold></color>"
				+ (sub == null ? "" : "<#5A5A5A> · " + sub);
		return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(text);
	}

	/** Pixel width of text in Minecraft's default font (bold adds 1 per glyph). */
	static int width(String s, boolean bold) {
		int w = 0;
		for (char ch : s.toCharArray()) {
			int g = switch (ch) {
				case 'i', '!', '.', ',', ':', ';', '|', '\'', '·' -> 2;
				case 'l', '`' -> 3;
				case 't', 'I', '[', ']', ' ' -> 4;
				case 'f', 'k', '<', '>', '(', ')', '{', '}', '"', '*' -> 5;
				case '@', '~', '◆' -> 7;
				default -> 6;
			};
			w += g + (bold && ch != ' ' ? 1 : 0);
		}
		return w;
	}

	/** Rows that look balanced for n items: split evenly instead of 7 + a straggler (e.g. 10 -> 5 + 5). */
	static List<Integer> balanced(int firstRow, int maxRows, int n) {
		int rows = Math.max(1, Math.min(maxRows, (n + 6) / 7));
		List<Integer> out = new ArrayList<>();
		int left = n;
		for (int r = 0; r < rows && left > 0; r++) {
			int k = (int) Math.ceil(left / (double) (rows - r));
			k = Math.min(7, k);
			for (int c : columns(k)) out.add((firstRow + r) * 9 + c);
			left -= k;
		}
		return out;
	}
}
