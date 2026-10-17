const $ = (id) => document.getElementById(id);
async function refresh() {
  const { port } = await chrome.storage.local.get({ port: 47321 });
  $('port').value = port;
  const h = await chrome.runtime.sendMessage({ type: 'health' });
  if (h && h.ok) {
    $('dot').className = 'dot ok';
    $('status').textContent = `server running · Java ${h.java}`;
  } else {
    $('dot').className = 'dot bad';
    $('status').textContent = 'server offline';
  }
}
$('save').addEventListener('click', async () => {
  const port = parseInt($('port').value, 10) || 47321;
  await chrome.storage.local.set({ port });
  refresh();
});
refresh();
