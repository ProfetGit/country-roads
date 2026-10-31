-- Country Roads banner art: flat apricot background (192x64 cells), lettering COUNTRY / ROADS and the tagline.
--   dofile("/home/emppu/Projects/Minecraft Datapacks/mods/CountryRoads/dev/icon/draw_banner.lua")
dofile("/home/emppu/Projects/Minecraft Datapacks/.claude/skills/pack-icon-animation/assets/pixel_art.lua")
local OUT = "/home/emppu/Projects/Minecraft Datapacks/mods/CountryRoads/dev/icon/sprites/"
local APRICOT = "#E08C52"

local function flat(name, w, h, marks)
  local img = Image(w, h, ColorMode.RGB)
  local c = Color(PA.hex(APRICOT))
  for y = 0, h - 1 do for x = 0, w - 1 do img:drawPixel(x, y, c) end end
  for _, s in ipairs(marks or {}) do
    img:drawPixel(s[1], s[2], Color(PA.hex(s[3])))
    if s[4] then
      for _, d in ipairs({ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) do img:drawPixel(s[1] + d[1], s[2] + d[2], Color(PA.hex("#EC9A60"))) end
    end
  end
  local spr = Sprite(w, h, ColorMode.RGB)
  spr.cels[1].image = img
  spr:saveAs(OUT .. name .. ".aseprite")
  spr:saveCopyAs(OUT .. name .. ".png")
  spr:close()
  print("saved " .. name)
end
local pts = { { 9, 8 }, { 27, 17 }, { 44, 6 }, { 61, 12 }, { 80, 5 }, { 96, 9 }, { 104, 21 }, { 118, 4 }, { 131, 14 }, { 150, 7 }, { 163, 18 }, { 176, 5 }, { 186, 12 },
  { 14, 52 }, { 38, 58 }, { 60, 49 }, { 183, 55 }, { 170, 46 }, { 120, 56 }, { 90, 52 } }
local marks = {}
for n, p in ipairs(pts) do marks[#marks + 1] = { p[1], p[2], n % 3 == 0 and "#F2A86E" or "#EC9A60", n % 6 == 0 } end
flat("banner_bg", 192, 64, marks)

local CREAM = { "#FFFFFF", "#FFF7E6", "#FFF7E6", "#FFF7E6", "#F6E2BE", "#F6E2BE", "#F6E2BE", "#E8C99A", "#E8C99A", "#E8C99A" }
local SUN = { "#FFF6C2", "#FFE680", "#FFE680", "#FFE680", "#FFD04A", "#FFD04A", "#FFD04A", "#F5A623", "#F5A623", "#F5A623" }
local EXTRUDE = { "#8A4B25", "#5E3018" }
PA.title_sprite(OUT .. "banner_title_top", "COUNTRY", { bands = CREAM, extrude = EXTRUDE, outline = "#2E1A10" })
PA.title_sprite(OUT .. "banner_title", "ROADS", { bands = SUN, extrude = EXTRUDE, outline = "#2E1A10" })
PA.label_sprite(OUT .. "banner_tagline", "A ROAD TO EVERY DOOR.", function(i) return i > 15 and "#FFE680" or "#FFFFFF" end, "#2E1A10")
