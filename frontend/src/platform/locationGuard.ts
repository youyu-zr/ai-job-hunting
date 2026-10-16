export type LocationGuardConfig = {
    enabled?: boolean
    resumeCity?: string
    targetCities?: string[]
    mismatchThreshold?: number
}

export type LocationSnapshot = {
    searchCity?: string
    jobCity?: string
}

export type LocationGuardResult = {
    action: 'ALLOW' | 'WARN' | 'PAUSE'
    reason: string
    nextMismatchCount: number
    expectedCities: string[]
    detectedCities: string[]
}

const SEARCH_CITY_SELECTORS = [
    '[ka="header-city"]',
    '.header-city',
    '.city-selector',
    '.city-select',
    '.search-city',
    '[class*="header-city"]',
    '[class*="city-select"]',
]

const CITY_LABEL_PREFIX = /^(当前城市|选择城市|切换城市|工作城市|求职城市|城市)[:：\s]*/
const NON_CITY_TEXT = /^(城市|选择城市|切换城市|工作地点|职位地点|全国|不限)$/

export function normalizeCityName(value: unknown): string {
    const text = String(value ?? '')
        .replace(/[\r\n\t\s]+/g, '')
        .replace(/[()（）【】\[\]，。,:：/\\|]/g, '')
        .replace(/(特别行政区|自治区|开发区|新区|市|省|区|县)/g, '')
    return text
}

function cleanCityCandidate(value: unknown): string {
    const text = String(value ?? '')
        .replace(/[\r\n\t]+/g, ' ')
        .replace(CITY_LABEL_PREFIX, '')
        .replace(/\s+/g, ' ')
        .trim()
    if (!text || NON_CITY_TEXT.test(text) || text.length > 16) {
        return ''
    }

    const explicit = text.match(/([\u4e00-\u9fa5]{2,8})(?:市|区|县)?(?:[\s>/-]|$)/)
    const candidate = explicit?.[1] || text
    const normalized = normalizeCityName(candidate)
    if (normalized.length < 2 || normalized.length > 8 || NON_CITY_TEXT.test(normalized)) {
        return ''
    }
    return normalized
}

export function readSearchCity(root: ParentNode | {querySelectorAll: (selector: string) => ArrayLike<{textContent?: string | null}>}): string {
    for (const selector of SEARCH_CITY_SELECTORS) {
        const nodes = Array.from(root.querySelectorAll(selector))
        for (const node of nodes) {
            const city = cleanCityCandidate(node.textContent)
            if (city) {
                return city
            }
        }
    }
    return ''
}

function cityMatches(location: string, city: string): boolean {
    const normalizedLocation = normalizeCityName(location)
    const normalizedCity = normalizeCityName(city)
    return Boolean(normalizedLocation && normalizedCity
        && (normalizedLocation.includes(normalizedCity) || normalizedCity.includes(normalizedLocation)))
}

function uniqueCities(values: unknown[]): string[] {
    return Array.from(new Set(values.map(normalizeCityName).filter(Boolean)))
}

export function evaluateLocationConsistency(
    snapshot: LocationSnapshot,
    config: LocationGuardConfig,
    mismatchCount: number,
): LocationGuardResult {
    const expectedCities = uniqueCities([
        ...(Array.isArray(config.targetCities) ? config.targetCities : []),
    ])
    const resumeCity = normalizeCityName(config.resumeCity)
    const threshold = Math.max(1, Number(config.mismatchThreshold) || 2)
    const detectedCities = uniqueCities([snapshot.searchCity, snapshot.jobCity])

    if (!config.enabled || expectedCities.length === 0) {
        return {
            action: 'ALLOW',
            reason: '地区保护未启用或尚未配置目标城市',
            nextMismatchCount: 0,
            expectedCities,
            detectedCities,
        }
    }

    const jobKnown = Boolean(normalizeCityName(snapshot.jobCity))
    const searchKnown = Boolean(normalizeCityName(snapshot.searchCity))
    const jobMatches = !jobKnown || expectedCities.some(city => cityMatches(snapshot.jobCity || '', city))
    const searchMatches = !searchKnown || expectedCities.some(city => cityMatches(snapshot.searchCity || '', city))
    const mismatch = !jobMatches || !searchMatches

    if (!mismatch) {
        return {
            action: 'ALLOW',
            reason: resumeCity && snapshot.jobCity && !cityMatches(snapshot.jobCity, resumeCity)
                ? `职位地点为${snapshot.jobCity}，简历/现居城市为${resumeCity}，已按目标城市继续核验`
                : '职位地点与当前目标城市一致',
            nextMismatchCount: 0,
            expectedCities,
            detectedCities,
        }
    }

    const nextMismatchCount = Math.max(0, mismatchCount) + 1
    const detected = detectedCities.length > 0 ? detectedCities.join('、') : '未读取到'
    const reason = `当前目标城市为${expectedCities.join('、')}，检测到搜索/职位地点为${detected}`
        + (resumeCity ? `；简历/现居城市为${resumeCity}` : '')
        + `；连续异地次数 ${nextMismatchCount}/${threshold}`

    return {
        action: nextMismatchCount >= threshold ? 'PAUSE' : 'WARN',
        reason,
        nextMismatchCount,
        expectedCities,
        detectedCities,
    }
}
