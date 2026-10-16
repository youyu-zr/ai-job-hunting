import {allowedPlatformBodyTypeOf} from '../webSocket/packetMessage.ts';

export function batchPlatformBodyTypeOf(items: ReadonlyArray<{msgObj: unknown}>): number | undefined {
    const types = items.map(item => allowedPlatformBodyTypeOf(item.msgObj));
    // Any interview card keeps the server's mandatory confirmation semantics.
    if (types.includes(14)) {
        return 14;
    }
    if (types.includes(1)) {
        return 1;
    }
    // Do not let one system event silently consume unrelated HR content.
    return types.every(type => type === types[0]) ? types[0] : undefined;
}
