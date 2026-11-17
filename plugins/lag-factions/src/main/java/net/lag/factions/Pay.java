package net.lag.factions;

import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.integration.Econ;
import dev.kitteh.factions.permissible.Role;

/**
 * Paying for upgrades and shop items: the buyer picks "my money" or "faction bank" in a small menu, so nobody has to
 * deposit first. Spending from the bank needs mod or higher. Money goes through FactionsUUID's economy (Vault).
 */
final class Pay {
	private Pay() { }

	static boolean ready() {
		return Econ.shouldBeUsed();
	}

	static double personal(Player p) {
		FPlayer fp = F.fp(p);
		return fp == null || !ready() ? 0 : Econ.getBalance(fp);
	}

	static double bank(Faction f) {
		return f == null || !Econ.shouldBeUsedWithBanks() ? 0 : Econ.getBalance(f);
	}

	static boolean canUseBank(Player p) {
		return Econ.shouldBeUsedWithBanks() && F.atLeast(p, Role.MODERATOR);
	}

	/** Opens the "who pays" menu; onPaid runs after the money is taken. */
	static void choose(Player p, String what, double cost, Material icon, Runnable back, Consumer<Player> onPaid) {
		if (!ready()) { Ui.err(p, "The economy isn't set up on this server yet."); return; }
		Faction f = F.of(p);
		double mine = personal(p), bank = bank(f);
		boolean bankOk = f != null && canUseBank(p);
		Gui g = new Gui(3, Ui.GRAD + "<bold>CONFIRM</bold></gradient> <dark_gray>• " + Ui.money(cost));
		g.frame();
		g.set(4, Ui.item(icon, true, what, Ui.LABEL + "Price <white>" + Ui.money(cost)));
		g.set(11, Ui.item(mine >= cost ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
				(mine >= cost ? Ui.GREEN : Ui.DIM) + "<bold>PAY WITH MY MONEY",
				Ui.LABEL + "You have <white>" + Ui.money(mine),
				mine >= cost ? Ui.CYAN + "Click to buy" : Ui.RED + "Not enough"), c -> {
			if (personal(c.player()) < cost) { Ui.err(c.player(), "You don't have " + Ui.money(cost) + "."); return; }
			if (!Econ.modifyMoney(F.fp(c.player()), -cost, null, null)) { Ui.err(c.player(), "Payment failed."); return; }
			c.player().closeInventory();
			onPaid.accept(c.player());
		});
		g.set(15, Ui.item(bankOk && bank >= cost ? Material.PURPLE_CONCRETE : Material.GRAY_CONCRETE,
				(bankOk && bank >= cost ? Ui.PURPLE : Ui.DIM) + "<bold>PAY FROM FACTION BANK",
				Ui.LABEL + "Bank has <white>" + (f == null ? "-" : Ui.money(bank)),
				f == null ? Ui.RED + "No faction" : !bankOk ? Ui.RED + "Mods and up only" : bank >= cost ? Ui.CYAN + "Click to buy" : Ui.RED + "Not enough"), c -> {
			Faction ff = F.of(c.player());
			if (ff == null || !canUseBank(c.player())) { Ui.err(c.player(), "Only mods and up can spend the faction bank."); return; }
			if (bank(ff) < cost) { Ui.err(c.player(), "The bank doesn't have " + Ui.money(cost) + "."); return; }
			if (!Econ.modifyMoney(ff, -cost, null, null)) { Ui.err(c.player(), "Payment failed."); return; }
			c.player().closeInventory();
			for (Player o : ff.membersOnlineAsPlayers())
				Ui.msg(o, "<white>" + Ui.esc(c.player().getName()) + Ui.LABEL + " spent <white>" + Ui.money(cost) + Ui.LABEL + " from the bank on " + what);
			onPaid.accept(c.player());
		});
		g.set(22, Ui.item(Material.ARROW, Ui.LABEL + "Back"), c -> back.run());
		g.open(p);
	}
}
