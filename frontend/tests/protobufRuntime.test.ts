import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import protobuf from "protobufjs";
import ts from "typescript";

test("the bundled protobuf runtime round-trips the fixed BOSS text-message schema", () => {
    const source = readFileSync(new URL("../src/webSocket/protobuf.ts", import.meta.url), "utf8");
    const file = ts.createSourceFile("protobuf.ts", source, ts.ScriptTarget.Latest, true);
    const declaration = file.statements
        .filter(ts.isVariableStatement)
        .flatMap(statement => [...statement.declarationList.declarations])
        .find(item => ts.isIdentifier(item.name) && item.name.text === "protoDefinition");
    const schema = declaration?.initializer;

    assert.ok(schema && ts.isStringLiteral(schema), "BOSS schema must remain a source-controlled string");

    const protocol = protobuf.parse(schema.text).root.lookupType("TechwolfChatProtocol");
    const bytes = protocol.encode({
        type: 1,
        messages: [{
            from: {uid: "1001", source: 0},
            to: {uid: "2002", name: "synthetic-recipient", source: 0},
            type: 1,
            mid: "1730000000000",
            time: "1730000000000",
            body: {type: 1, templateId: 1, text: "synthetic message"},
            cmid: "1730000000000",
        }],
    }).finish();
    const decoded = protocol.decode(bytes) as any;

    assert.equal(decoded.type, 1);
    assert.equal(decoded.messages[0].from.uid.toString(), "1001");
    assert.equal(decoded.messages[0].to.uid.toString(), "2002");
    assert.equal(decoded.messages[0].body.text, "synthetic message");
});
