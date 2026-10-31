-- Country Roads description-kit sprites (tools/Description-Kit reads dev/icon/sprites/kit_*.png): two flowers as the caps of
-- the section lines and a gallows sign as the footer divider's centre piece. 16x16, same palette as draw_sprites.lua.
--   dofile("/home/emppu/Projects/Minecraft Datapacks/mods/CountryRoads/dev/icon/draw_kit.lua")
dofile("/home/emppu/Projects/Minecraft Datapacks/.claude/skills/pack-icon-animation/assets/pixel_art.lua")
local OUT = "/home/emppu/Projects/Minecraft Datapacks/mods/CountryRoads/dev/icon/sprites/"

local function flower(name, hi, petal, shade, centre, centre2)
  PA.sprite_from_grid(OUT .. name, {
    "................",
    "......hhPP......",
    ".....hPPPPs.....",
    "....hPPPPPPs....",
    "....PPPyyPPs....",
    "....PPPyYPPs....",
    "....hPPPPPss....",
    ".....PPPPss.....",
    "......ssss......",
    "......gg........",
    "..GG..gg........",
    ".GGGg.gg.gGG....",
    "..GGgggg.gGGG...",
    "....gggg..GG....",
    "......gg........",
    "......gg........",
  }, { h = hi, P = petal, s = shade, y = centre, Y = centre2, g = "#46A046", G = "#6CC24F" })
end
flower("kit_flower_red", "#FF8A7A", "#E03A3A", "#B02A2A", "#FFD23F", "#C2641A")
flower("kit_flower_blue", "#A8C4FF", "#5B8CFF", "#3A62C8", "#FFFFFF", "#FFD23F")

PA.sprite_from_grid(OUT .. "kit_sign_item", {
  "wwWWWWWWWWWWWW..",
  "wWDDDDDDDDDDDD..",
  "wWD..c....c.....",
  "wWD..c....c.....",
  "wWD.bbbbbbbbbb..",
  "wWD.bLLLLLLLLb..",
  "wWD.bLttLtttLb..",
  "wWD.bLLLLLLLLbb.",
  "wWD.bBBBBBBBBBb.",
  "wWD.bbbbbbbbbb..",
  "wWD.............",
  "wWD.............",
  "wWD.............",
  "wWD.............",
  "wWD.............",
  "wWD.............",
}, { w = "#C9A267", W = "#A67C42", D = "#7E5A2C", c = "#8F8C96", b = "#5E3F1B", L = "#E3B96F", B = "#C99350", t = "#3A2412" })
print("kit done")
