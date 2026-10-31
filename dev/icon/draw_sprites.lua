-- Country Roads icon sprites (pack-icon-animation skill). Run through the aseprite MCP:
--   dofile("/home/emppu/Projects/Minecraft Datapacks/mods/CountryRoads/dev/icon/draw_sprites.lua")
-- Everything is drawn from scratch with a seeded generator plus hand-placed details: grass and road blocks (top and the two
-- shaded side faces), the gallows signpost, wildflowers, debris chunks. (The Surveyor's Stake was dropped from the icon at the user's request.) FX (star, ring, spark, dust) are our own sprites copied by build_sprites.sh.
-- Face textures sit in the top-left of a 16x16 canvas (Blockbench treats taller-than-wide images as animated strips).
dofile("/home/emppu/Projects/Minecraft Datapacks/.claude/skills/pack-icon-animation/assets/pixel_art.lua")
local OUT = "/home/emppu/Projects/Minecraft Datapacks/mods/CountryRoads/dev/icon/sprites/"
local key = PA.key

-- ramps, darkest (extra shade) to lightest
local RAMP = {
  grass = { "#16392A", "#1F4F3A", "#2E7A3E", "#46A046", "#6CC24F", "#A0DD63", "#D2F58A" },
  dirt  = { "#2A180C", "#3E2514", "#5E3A1F", "#81532B", "#A8743F", "#C99A5E" },
  road  = { "#4A2D17", "#6B4426", "#8E6236", "#B28348", "#D3A965", "#EACB8C" },
  stone = { "#4A4852", "#66636F", "#8F8C96", "#B9B6BC", "#DCD9DC" },
  wood  = { "#1E1206", "#2E1D0B", "#5E3F1B", "#7E5A2C", "#A67C42", "#C9A267" },
}
local g, d, r, p, w = RAMP.grass, RAMP.dirt, RAMP.road, RAMP.stone, RAMP.wood

local seed = 20261018
local function rnd() seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648 end

local function canvas() return { px = {} } end
local function set(c, x, y, col) c.px[key(x, y)] = col end
local function rect(c, x, y, wd, ht, col) for j = y, y + ht - 1 do for i = x, x + wd - 1 do set(c, i, j, col) end end end
local function save(c, name, wd, ht) PA.save_pixels(OUT .. name, wd or 16, ht or 16, c.px) end
local function grid(c, x0, y0, rows, legend)
  for j, row in ipairs(rows) do
    for i = 1, #row do
      local ch = row:sub(i, i)
      if legend[ch] then set(c, x0 + i - 1, y0 + j - 1, legend[ch]) end
    end
  end
end
-- scatter: list of {colour, probability}
local function speckle(c, x0, y0, x1, y1, base, list)
  for y = y0, y1 do for x = x0, x1 do
    local col, v = base, rnd()
    local acc = 0
    for _, s in ipairs(list) do acc = acc + s[2]; if v < acc then col = s[1]; break end end
    set(c, x, y, col)
  end end
end
local function bevel(c, light, dark, x0, y0, x1, y1)
  for x = x0, x1 do set(c, x, y0, light); set(c, x, y1, dark) end
  for y = y0 + 1, y1 - 1 do set(c, x0, y, light); set(c, x1, y, dark) end
  set(c, x0, y1, dark); set(c, x1, y0, light)
end
local function pebble(c, x, y, big)
  set(c, x, y, p[4]); set(c, x + 1, y, p[3])
  set(c, x, y + 1, p[3]); set(c, x + 1, y + 1, p[2])
  if big then set(c, x + 2, y + 1, p[2]) end
end

-- ---- block faces ----
do
  local c = canvas()
  speckle(c, 0, 0, 15, 15, g[5], { { g[4], 0.22 }, { g[6], 0.13 }, { g[3], 0.04 } })
  for _, t in ipairs({ { 3, 4 }, { 10, 2 }, { 6, 11 }, { 12, 9 }, { 2, 13 } }) do
    set(c, t[1], t[2], g[3]); set(c, t[1] + 1, t[2], g[4]); set(c, t[1], t[2] - 1, g[6])
  end
  bevel(c, g[6], g[4], 0, 0, 15, 15)
  save(c, "grass_top")
end
do
  local c = canvas()
  speckle(c, 0, 4, 15, 15, d[4], { { d[3], 0.24 }, { d[5], 0.12 }, { d[2], 0.05 } })
  pebble(c, 3, 8); pebble(c, 10, 11)
  speckle(c, 0, 0, 15, 3, g[5], { { g[4], 0.22 }, { g[6], 0.12 } })
  local drip = { 1, 2, 1, 0, 2, 1, 1, 3, 1, 0, 2, 1, 1, 0, 2, 1 }
  for x = 0, 15 do for y = 4, 3 + drip[x + 1] do set(c, x, y, g[4]) end end
  for x = 0, 15 do set(c, x, 3 + drip[x + 1], g[3]) end
  bevel(c, d[5], d[3], 0, 0, 0, 15)
  for x = 0, 15 do set(c, x, 15, d[3]) end
  for y = 4, 14 do set(c, 15, y, d[3]) end
  for y = 0, 3 do set(c, 0, y, g[6]) end
  save(c, "grass_side")
end
do
  local c = canvas()
  speckle(c, 0, 0, 15, 15, r[4], { { r[3], 0.2 }, { r[5], 0.16 }, { r[2], 0.05 }, { r[6], 0.04 } })
  for _, t in ipairs({ { 2, 3, false }, { 10, 2, true }, { 5, 9, false }, { 11, 11, true }, { 2, 13, false }, { 13, 6, false } }) do pebble(c, t[1], t[2], t[3]) end
  bevel(c, r[6], r[3], 0, 0, 15, 15)
  save(c, "road_top")
end
do
  local c = canvas()
  speckle(c, 0, 0, 15, 15, d[4], { { d[3], 0.24 }, { d[5], 0.12 }, { d[2], 0.05 } })
  speckle(c, 0, 0, 15, 1, r[5], { { r[4], 0.35 }, { r[6], 0.15 } })
  for x = 0, 15 do set(c, x, 2, (x % 3 == 0) and r[4] or d[5]) end
  pebble(c, 4, 7); pebble(c, 11, 10)
  bevel(c, d[5], d[3], 0, 0, 0, 15)
  for x = 0, 15 do set(c, x, 15, d[3]) end
  for y = 0, 14 do set(c, 15, y, d[3]) end
  save(c, "road_side")
end

-- darker copies for the left (1 step) and right (2 steps) faces; every ramp shifts together
local function shadeMap(steps)
  local m = {}
  for _, ramp in pairs(RAMP) do
    for i = 2, #ramp do m[ramp[i]] = ramp[math.max(1, i - steps)] end
  end
  return m
end
for _, n in ipairs({ "grass_side", "road_side" }) do
  PA.remap(OUT .. n .. ".aseprite", OUT .. n .. "_left", shadeMap(1))
  PA.remap(OUT .. n .. ".aseprite", OUT .. n .. "_right", shadeMap(2))
end
-- grass_top / road_top get side copies too: used by the thin top shell while a tile flips
PA.whiten(OUT .. "road_top.aseprite", OUT .. "hit_flash", 0.72)

-- ---- gallows signpost (the mod's decor): post + arm, and the hanging board on two chains ----
do
  local c = canvas()
  for y = 0, 21 do set(c, 1, y, w[5]); set(c, 2, y, w[4]); set(c, 3, y, w[3]) end
  set(c, 1, 0, w[6]); set(c, 2, 0, w[6]); set(c, 3, 0, w[5])
  for x = 4, 19 do set(c, x, 0, w[5]); set(c, x, 1, w[4]); set(c, x, 2, w[3]) end
  set(c, 20, 1, w[3]); set(c, 20, 2, w[2])
  for _, y in ipairs({ 6, 11, 16 }) do set(c, 2, y, w[3]); set(c, 3, y, w[2]) end
  set(c, 4, 3, w[3]); set(c, 5, 3, w[2]); set(c, 4, 4, w[2])
  save(c, "signpost_post", 22, 22)
end
do
  local c = canvas()
  local ch1, ch2 = p[4], p[2]
  for _, x in ipairs({ 2, 12 }) do set(c, x, 0, ch1); set(c, x, 1, ch2); set(c, x, 2, ch1) end
  local O = { "#E3B96F", "#C99350", "#A87238", "#5E3F1B" }
  local rows = {
    "11111111111110.",
    "122222222222221",
    "1233333333333331",
  }
  -- board: x 0..14, y 3..9; right end pointed
  for y = 3, 9 do
    local x1 = 13
    if y == 3 or y == 9 then x1 = 11 elseif y == 4 or y == 8 then x1 = 13 else x1 = 14 end
    for x = 0, x1 do
      local col = O[2]
      if y == 3 then col = O[1] elseif y == 9 then col = O[3] end
      if x == 0 then col = O[4] end
      set(c, x, y, col)
    end
  end
  for x = 0, 11 do set(c, x, 3, O[4]) end
  for x = 0, 11 do set(c, x, 9, O[4]) end
  for y = 4, 8 do set(c, 0, y, O[4]) end
  set(c, 12, 4, O[4]); set(c, 13, 5, O[4]); set(c, 14, 6, O[4]); set(c, 13, 7, O[4]); set(c, 12, 8, O[4])
  set(c, 12, 3, nil); set(c, 12, 9, nil)
  local T = "#3A2412"
  for x = 2, 9 do if x ~= 5 then set(c, x, 5, T) end end
  for x = 2, 6 do if x ~= 4 then set(c, x, 7, T) end end
  set(c, 10, 7, T); set(c, 11, 7, T)
  save(c, "sign_board", 16, 16)
end

-- ---- wildflowers on the verge (8x8) ----
local function flower(name, petal, hi, centre)
  local c = canvas()
  local L = { R = petal, h = hi, Y = centre, g = g[4], G = g[3] }
  grid(c, 0, 0, {
    "..hRR...",
    ".hRYRR..",
    "..RRR...",
    "...g....",
    "...gG...",
    "..gg....",
    "...g.g..",
    "...gg...",
  }, L)
  save(c, name, 8, 8)
end
flower("flower_red", "#E03A3A", "#FF8A7A", "#FFD23F")
flower("flower_yellow", "#FFD23F", "#FFF08A", "#C2641A")
flower("flower_blue", "#5B8CFF", "#A8C4FF", "#FFFFFF")

-- ---- debris chunks (4x4 each): grass, dirt, road ----
do
  local c = canvas()
  local sets = { { g[6], g[5], g[4], g[3] }, { d[5], d[4], d[3], d[2] }, { r[6], r[4], r[3], r[2] } }
  for i, s in ipairs(sets) do
    local x0 = (i - 1) * 4
    rect(c, x0, 0, 4, 1, s[1]); rect(c, x0, 1, 4, 2, s[2]); rect(c, x0, 3, 4, 1, s[3])
    set(c, x0 + 3, 1, s[3]); set(c, x0 + 3, 2, s[3]); set(c, x0, 1, s[1])
  end
  save(c, "chunks")
end
print("done")
