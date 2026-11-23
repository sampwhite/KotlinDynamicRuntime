package com.dynamicruntime.webapp

/**
 * Hands [text] to the browser to save as [fileName]: a Blob behind a transient link, so nothing leaves the page and
 * no server holds the file. The leading byte-order mark is what makes Excel read the file as UTF-8. A report's CSV and
 * the Files page's copy of a CSV table are saved this way.
 */
fun saveTextFile(fileName: String, mimeType: String, text: String) {
    js(
        """
        (function () {
            var blob = new Blob(['\uFEFF' + text], { type: mimeType });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
        })()
        """,
    )
}
