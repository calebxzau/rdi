# GU4c1 owner checklist

Data folder used by the agent: `evidence/ui-port/u4c1/live/run` (saved account removed).
`live/preview.ps1` starts `cargo preview-run` there with the service disabled on purpose;
`RDI_PROTO_SERVER=http://127.0.0.1:65231` points it at the local master.

## Decisions

- [x] Map template cards: keep name · terrain · size, or show the owner like Compose? → show the author (done 2026-11-23)
- [x] Keep the local test accounts and the room 11111的1.21 in the local Mongo, or remove them? → keep

## Slint launcher

- [ ] Look of the details page, the form and the dialogs next to Compose.
- [ ] Create a room from a version page (each terrain, template, required template).
- [ ] Members: invite, set/remove admin, kick, transfer, leave; refusal messages.
- [ ] Plain member: only 退出房间 on the own chip, no menu on others.
- [ ] Edit and save, 更新整合包, delete (both room generations).
- [ ] Escape and click outside close the guide, the member menu and the dialogs.
- [ ] hid/mid/wid copy to the clipboard.
- [ ] Right click on the 多人房间 tab does nothing.
