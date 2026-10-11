export function downloadJsonFile(fileName: string, payload: unknown) {
    const json = JSON.stringify(payload, null, 2);
    const blob = new Blob([json], {type: 'application/json;charset=utf-8'});
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    return json;
}

export async function readJsonFile(file: File) {
    const text = await file.text();
    return JSON.parse(text);
}
