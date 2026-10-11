type AnyRecord = Record<string, any>;

function componentCandidates(component: any): AnyRecord[] {
    if (!component) {
        return [];
    }
    return [
        component._props,
        component.props,
        component.$props,
        component.vnode?.props,
        component.proxy,
        component.setupState,
        component.data,
    ].filter((candidate): candidate is AnyRecord => Boolean(candidate && typeof candidate === 'object'));
}

function readComponentData(element: any): AnyRecord | undefined {
    let elementCursor = element;
    for (let elementDepth = 0; elementCursor && elementDepth < 5; elementDepth += 1) {
        let component = elementCursor.__vueParentComponent || elementCursor.__vue__;
        for (let componentDepth = 0; component && componentDepth < 5; componentDepth += 1) {
            const candidates = componentCandidates(component);
            const data = candidates.find(candidate =>
                candidate.source !== undefined
                || candidate.lastMessageFromUid !== undefined
                || candidate.lastMsgFromUid !== undefined
                || candidate.lastMessageText !== undefined
                || candidate.lastMsgText !== undefined
            );
            if (data) {
                return data;
            }
            component = component.parent || component.$parent;
        }
        elementCursor = elementCursor.parentElement;
    }
    return undefined;
}

function readDataAttributes(element: any): AnyRecord {
    const get = (name: string) => element?.getAttribute?.(name) || element?.dataset?.[name.replace(/^data-/, '').replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())];
    const result: AnyRecord = {};
    const values: Record<string, string[]> = {
        uid: ['data-uid', 'data-user-id', 'data-boss-id'],
        encryptJobId: ['data-encrypt-job-id', 'data-job-id'],
        securityId: ['data-security-id'],
        lid: ['data-lid'],
        lastMessageFromUid: ['data-last-message-from-uid', 'data-last-sender-id'],
        lastMessageText: ['data-last-message-text', 'data-last-msg-text'],
        lastMessageFromMe: ['data-last-message-from-me', 'data-last-message-is-mine'],
    };
    for (const [key, names] of Object.entries(values)) {
        const value = names.map(get).find(Boolean);
        if (value !== undefined && value !== '') {
            result[key] = value;
        }
    }
    return result;
}

export function readElementComponentData(element: any): AnyRecord {
    return readComponentData(element) || {};
}

export function readFriendSource(element: any): AnyRecord {
    const data = readComponentData(element);
    const source = data?.source;
    const attributes = readDataAttributes(element);
    if (source && typeof source === 'object') {
        return {...attributes, ...source};
    }
    return {...attributes, ...(data || {})};
}
