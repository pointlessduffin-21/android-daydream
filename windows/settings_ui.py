"""Native settings window and validation for Daydream's JSON configuration."""
from __future__ import annotations

import copy
import json
import os
import tempfile
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import core


def config_from_fields(cfg: dict, fields: dict) -> dict:
    """Validate editor values before modifying the saved configuration."""
    result = copy.deepcopy(cfg)
    for name, value in fields.items():
        parts = name.split('.')
        target = result
        for part in parts[:-1]:
            target = target[part]
        target[parts[-1]] = value
    try:
        interval = int(fields['interval_seconds'])
        display = int(fields['display'])
    except (ValueError, TypeError):
        raise ValueError('Interval and monitor must be whole numbers.') from None
    if not 5 <= interval <= 300:
        raise ValueError('Photo interval must be between 5 and 300 seconds.')
    if display < 0:
        raise ValueError('Monitor index must be zero or higher.')
    result['interval_seconds'], result['display'] = interval, display
    result['folders'] = [s.strip() for s in fields['folders'].splitlines() if s.strip()]
    result['immich']['albums'] = [s.strip() for s in fields['immich.albums'].replace(',', '\n').splitlines() if s.strip()]
    result['weather']['city'] = result['weather']['city'].strip()
    for key, choices in core.CHOICES.items():
        if result[key] not in choices:
            raise ValueError(f'Choose a valid {key.replace("_", " ")}.')
    if result['immich']['mode'] not in {'random', 'favorites', 'albums'}:
        raise ValueError('Choose a valid Immich photo selection mode.')
    if result['photo_source'] == 'local' and not result['folders']:
        raise ValueError('Add at least one photo folder, or select no photos.')
    if result['photo_source'] == 'immich':
        im = result['immich']
        im['url'], im['api_key'] = im['url'].strip(), im['api_key'].strip()
        try:
            core.Immich.from_config(im)
            for album in im['albums']:
                core._check_id(album)
        except core.ImmichError as exc:
            raise ValueError(str(exc)) from exc
        if im['mode'] == 'albums' and not im['albums']:
            raise ValueError('Enter at least one album ID for albums mode.')
    return result


def save_config(path: str, cfg: dict) -> None:
    """Replace the config atomically, preserving the original if writing fails."""
    path = os.path.abspath(path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.daydream-', suffix='.json', dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(cfg, stream, indent=2, ensure_ascii=False)
            stream.write('\n')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def show_settings(cfg: dict, path: str, error: str | None = None, displays: int = 1) -> bool:
    """Return True after saving; closing or cancelling leaves the file untouched."""
    root = tk.Tk()
    root.title('Daydream Settings')
    root.geometry('640x620')
    root.minsize(580, 580)
    root.configure(padx=20, pady=16)
    saved = False
    variables = {}
    ttk.Label(root, text='Daydream Settings', font=('Segoe UI', 20, 'bold')).pack(anchor='w')
    ttk.Label(root, text='Save to apply changes immediately.').pack(anchor='w', pady=(2, 14))
    notebook = ttk.Notebook(root)
    notebook.pack(fill='both', expand=True)

    def tab(title):
        frame = ttk.Frame(notebook, padding=16)
        frame.columnconfigure(1, weight=1)
        notebook.add(frame, text=title)
        return frame

    def field(frame, row, name, label, choices=None, password=False):
        value = cfg
        for part in name.split('.'):
            value = value[part]
        variable = tk.BooleanVar(value=value) if isinstance(value, bool) else tk.StringVar(value=value)
        variables[name] = variable
        if isinstance(value, bool):
            ttk.Checkbutton(frame, text=label, variable=variable).grid(row=row, column=0, columnspan=2, sticky='w', pady=7)
            return
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', padx=(0, 16), pady=7)
        widget = ttk.Combobox(frame, textvariable=variable, values=choices, state='readonly') if choices else ttk.Entry(frame, textvariable=variable, show='*' if password else '')
        widget.grid(row=row, column=1, sticky='ew', pady=7)

    photos = tab('Photos')
    field(photos, 0, 'photo_source', 'Photo source', ['local', 'immich', 'none'])
    field(photos, 1, 'interval_seconds', 'Interval (5–300 seconds)')
    field(photos, 2, 'layout', 'Layout', ['collages', 'single'])
    field(photos, 3, 'ken_burns', 'Animate photos with pan and zoom')
    ttk.Label(photos, text='Photo folders (one per line)').grid(row=4, column=0, columnspan=2, sticky='w', pady=(12, 6))
    folders = tk.Text(photos, height=6, wrap='none', font=('Segoe UI', 10))
    folders.insert('1.0', '\n'.join(cfg['folders']))
    folders.grid(row=5, column=0, columnspan=2, sticky='nsew')
    photos.rowconfigure(5, weight=1)

    def browse():
        folder = filedialog.askdirectory(parent=root, title='Choose a photo folder')
        if folder:
            current = folders.get('1.0', 'end-1c').strip()
            folders.delete('1.0', 'end')
            folders.insert('1.0', current + ('\n' if current else '') + folder)

    ttk.Button(photos, text='Add folder…', command=browse).grid(row=6, column=0, sticky='w', pady=8)
    ttk.Label(photos, text='Remove a folder by deleting its line.').grid(row=6, column=1, sticky='w')

    display = tab('Clock & Weather')
    field(display, 0, 'clock_24h', 'Use a 24-hour clock')
    field(display, 1, 'show_seconds', 'Show seconds on the digital clock')
    field(display, 2, 'night_mode', 'Night mode', ['auto', 'on', 'off'])
    ttk.Label(display, text='Auto night mode runs from 22:00 to 06:00.').grid(row=3, column=0, columnspan=2, sticky='w', pady=8)
    field(display, 4, 'display', 'Monitor index', [str(n) for n in range(displays)])
    field(display, 5, 'weather.city', 'Weather city')
    field(display, 6, 'weather.fahrenheit', 'Use Fahrenheit')
    ttk.Label(display, text='Leave the city empty to disable weather.').grid(row=7, column=0, columnspan=2, sticky='w', pady=8)

    immich = tab('Immich')
    field(immich, 0, 'immich.url', 'Server URL')
    field(immich, 1, 'immich.api_key', 'API key', password=True)
    field(immich, 2, 'immich.mode', 'Photo selection', ['random', 'favorites', 'albums'])
    album_value = tk.StringVar(value=', '.join(cfg['immich']['albums']))
    variables['immich.albums'] = album_value
    ttk.Label(immich, text='Album IDs (comma separated)').grid(row=3, column=0, sticky='w', padx=(0, 16), pady=7)
    ttk.Entry(immich, textvariable=album_value).grid(row=3, column=1, sticky='ew', pady=7)
    field(immich, 4, 'immich.allow_insecure_http', 'Allow HTTP for a non-local server')
    ttk.Label(immich, text='HTTP sends your API key without encryption.\nUse HTTPS for servers outside your local network.', wraplength=480).grid(row=5, column=0, columnspan=2, sticky='w', pady=12)

    ttk.Label(root, text=f'Configuration: {path}', wraplength=590).pack(anchor='w', pady=(12, 6))
    actions = ttk.Frame(root)
    actions.pack(fill='x')

    def save():
        nonlocal saved
        try:
            fields = {name: value.get() for name, value in variables.items()}
            fields['folders'] = folders.get('1.0', 'end-1c')
            updated = config_from_fields(cfg, fields)
            if int(updated['display']) >= displays:
                raise ValueError('Choose an available monitor index.')
            if error and not messagebox.askyesno('Replace invalid configuration?', f'{error}\n\nSave these settings over the invalid configuration?', parent=root):
                return
            save_config(path, updated)
        except (ValueError, OSError) as exc:
            messagebox.showerror('Could not save settings', str(exc), parent=root)
            return
        saved = True
        root.destroy()

    ttk.Button(actions, text='Save & Apply', command=save).pack(side='right')
    ttk.Button(actions, text='Cancel', command=root.destroy).pack(side='right', padx=8)
    root.bind('<Escape>', lambda event: root.destroy())
    root.bind('<Control-s>', lambda event: save())
    root.after(100, root.focus_force)
    root.mainloop()
    return saved
