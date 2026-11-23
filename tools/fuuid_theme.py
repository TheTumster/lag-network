"""RelapsedMC look for FactionsUUID's /f show (/f who, /f f): rewrites commands.show.normalFormat in translations.conf.
Usage: python3 tools/fuuid_theme.py path/to/plugins/FactionsUUID/config/translations.conf"""
import re, sys

BAR = "<gradient:#FF5C5C:#FFA19A><strikethrough>                    </strikethrough></gradient>"
BAR2 = "<gradient:#FFA19A:#FF5C5C><strikethrough>                    </strikethrough></gradient>"
D = "<dark_gray>»</dark_gray> "
SHOW = [
    f"{BAR} <faction:relation_color><bold><faction:name></bold> {BAR2}",
    f"{D}<gray>Description <white><faction:description>",
    f"{D}<gray>Land <#38E0FF><faction:claims_count></#38E0FF> <dark_gray>•</dark_gray> <gray>Power <#B040FF><faction:power></#B040FF><dark_gray>/</dark_gray><#B040FF><faction:power_max>",
    f"{D}<gray>Raidable <faction:if_raidable><#FF4D6D>Yes</faction:if_raidable><faction:if_raidable:else><#7CFF6B>No",
    f"{D}<gray>Joining <faction:if_open><#7CFF6B>Open</faction:if_open><faction:if_open:else><#FF4D6D>Invite only</faction:if_open:else><faction:if_peaceful> <dark_gray>•</dark_gray> <fuuid:color:peaceful>Peaceful",
    f"{D}<gray>Founded <white><faction:creation_date>",
    f"<faction:if_permanent>{D}<gray>This faction is permanent",
    f"<fuuid:if_economy><fuuid:if_banks>{D}<gray>Bank <white><faction:bank_balance></fuuid:if_banks>    <gray>Land value <white><faction:claims_value>",
    f"<faction:if_allies>{D}<gray>Allies <white>(<faction:allies_count>/<faction:allies_max>)</white>: {{allies-list}}",
    f"<faction:if_online>{D}<#7CFF6B>Online <white>(<faction:members_online_count>/<faction:members_total_count>)<faction:if_online>: {{online-list}}",
    f"<faction:if_offline>{D}<gray>Offline <white>(<faction:members_offline_count>/<faction:members_total_count>)<faction:if_offline>: {{offline-list}}",
    "<gradient:#FF5C5C:#FFA19A:#38E0FF><strikethrough>                                                       </strikethrough></gradient>",
]


def apply(text):
    body = ",\n".join('            "' + l.replace('"', '\\"') + '"' for l in SHOW)
    new, n = re.subn(r"(\n    show \{.*?normalFormat=\[)\n.*?\n(\s*\])", lambda m: m.group(1) + "\n" + body + "\n" + m.group(2), text, count=1, flags=re.S)
    # /f who and /f f as well as /f show
    new = re.sub(r"(\n    show \{\s*aliases=\[)\s*show\s*\]", r"\1\n            show,\n            who,\n            f\n        ]", new, count=1)
    return new, n


if __name__ == "__main__":
    p = sys.argv[1]
    t = open(p, encoding="utf-8").read()
    t2, n = apply(t)
    if n:
        open(p, "w", encoding="utf-8").write(t2)
    print(("themed " if n else "!! could not find show.normalFormat in ") + p)
