/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Event System
 *
 * Copyright 2010-2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
 * Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#include <freerdp/config.h>

#include <winpr/crt.h>
#include <winpr/input.h>

#include <freerdp/freerdp.h>
#include <freerdp/log.h>

#define TAG CLIENT_TAG("android")

#include "android_freerdp.h"
#include "android_cliprdr.h"

BOOL android_push_event(freerdp* inst, ANDROID_EVENT* event)
{
	androidContext* aCtx = (androidContext*)inst->context;
	ANDROID_EVENT_QUEUE* queue = aCtx->event_queue;

	/* Called from the Java UI thread while the session thread pops events: guard
	 * the array (it may be realloc'ed here) against concurrent access. */
	EnterCriticalSection(&queue->lock);

	if (queue->count >= queue->size)
	{
		int new_size;
		void* new_events;
		new_size = queue->size * 2;
		new_events = realloc((void*)queue->events, sizeof(ANDROID_EVENT*) * new_size);

		if (!new_events)
		{
			LeaveCriticalSection(&queue->lock);
			return FALSE;
		}

		queue->events = new_events;
		queue->size = new_size;
	}

	queue->events[(queue->count)++] = event;
	LeaveCriticalSection(&queue->lock);
	return SetEvent(queue->isSet);
}

static ANDROID_EVENT* android_peek_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event;

	if (queue->count < 1)
		return NULL;

	event = queue->events[0];
	return event;
}

static ANDROID_EVENT* android_pop_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event;

	if (queue->count < 1)
		return NULL;

	event = queue->events[0];
	(queue->count)--;

	for (size_t i = 0; i < queue->count; i++)
	{
		queue->events[i] = queue->events[i + 1];
	}

	return event;
}

static BOOL android_send_vk(rdpInput* input, DWORD vk, BOOL down)
{
	const DWORD scancode = GetVirtualScanCodeFromVirtualKeyCode(vk, 4);
	UINT16 flags = down ? KBD_FLAGS_DOWN : KBD_FLAGS_RELEASE;
	flags |= (scancode & KBDEXT) ? KBD_FLAGS_EXTENDED : 0;
	return freerdp_input_send_keyboard_event(input, flags, scancode & 0xFF);
}

/* Fallback when the server did not negotiate Unicode keyboard input: type the
 * character with scancodes as on a US keyboard (letters, digits, space and
 * punctuation). Characters without a key (e.g. Cyrillic) are dropped. */
static BOOL android_send_char_as_scancodes(rdpInput* input, UINT16 flags, UINT16 ch)
{
	static const struct
	{
		char c;
		DWORD vk;
		BOOL shift;
	} punct[] = {
		{ '-', VK_OEM_MINUS, FALSE },  { '_', VK_OEM_MINUS, TRUE },  { '=', VK_OEM_PLUS, FALSE },
		{ '+', VK_OEM_PLUS, TRUE },    { '[', VK_OEM_4, FALSE },     { '{', VK_OEM_4, TRUE },
		{ ']', VK_OEM_6, FALSE },      { '}', VK_OEM_6, TRUE },      { '\\', VK_OEM_5, FALSE },
		{ '|', VK_OEM_5, TRUE },       { ';', VK_OEM_1, FALSE },     { ':', VK_OEM_1, TRUE },
		{ '\'', VK_OEM_7, FALSE },     { '"', VK_OEM_7, TRUE },      { ',', VK_OEM_COMMA, FALSE },
		{ '<', VK_OEM_COMMA, TRUE },   { '.', VK_OEM_PERIOD, FALSE }, { '>', VK_OEM_PERIOD, TRUE },
		{ '/', VK_OEM_2, FALSE },      { '?', VK_OEM_2, TRUE },      { '`', VK_OEM_3, FALSE },
		{ '~', VK_OEM_3, TRUE },       { '!', '1', TRUE },           { '@', '2', TRUE },
		{ '#', '3', TRUE },            { '$', '4', TRUE },           { '%', '5', TRUE },
		{ '^', '6', TRUE },            { '&', '7', TRUE },           { '*', '8', TRUE },
		{ '(', '9', TRUE },            { ')', '0', TRUE },
	};
	const BOOL down = (flags & KBD_FLAGS_RELEASE) == 0;
	DWORD vk = 0;
	BOOL shift = FALSE;

	if (ch >= 'a' && ch <= 'z')
		vk = 'A' + (ch - 'a');
	else if (ch >= 'A' && ch <= 'Z')
	{
		vk = ch;
		shift = TRUE;
	}
	else if (ch >= '0' && ch <= '9')
		vk = ch;
	else if (ch == ' ')
		vk = VK_SPACE;
	else
	{
		for (size_t i = 0; i < ARRAYSIZE(punct); i++)
		{
			if ((UINT16)punct[i].c == ch)
			{
				vk = punct[i].vk;
				shift = punct[i].shift;
				break;
			}
		}
	}

	if (!vk)
	{
		if (down)
			WLog_WARN(TAG, "server has no Unicode input; cannot type U+%04X", ch);
		return TRUE; /* drop the character, keep the session */
	}

	if (down)
	{
		if (shift && !android_send_vk(input, VK_LSHIFT, TRUE))
			return FALSE;
		return android_send_vk(input, vk, TRUE);
	}

	if (!android_send_vk(input, vk, FALSE))
		return FALSE;
	return shift ? android_send_vk(input, VK_LSHIFT, FALSE) : TRUE;
}

static BOOL android_process_event(ANDROID_EVENT_QUEUE* queue, freerdp* inst)
{
	rdpContext* context;

	WINPR_ASSERT(queue);
	WINPR_ASSERT(inst);

	context = inst->context;
	WINPR_ASSERT(context);

	for (;;)
	{
		BOOL rc = FALSE;
		androidContext* afc = (androidContext*)context;
		ANDROID_EVENT* event = NULL;

		EnterCriticalSection(&queue->lock);
		event = android_pop_event(queue);
		LeaveCriticalSection(&queue->lock);

		if (!event)
			break;

		const int type = event->type;

		switch (event->type)
		{
			case EVENT_TYPE_KEY:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_keyboard_event(context->input, key_event->flags,
				                                       key_event->scancode);
			}
			break;

			case EVENT_TYPE_KEY_UNICODE:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				if (freerdp_settings_get_bool(context->settings, FreeRDP_UnicodeInput))
					rc = freerdp_input_send_unicode_keyboard_event(
					    context->input, key_event->flags, key_event->scancode);
				else
					rc = android_send_char_as_scancodes(context->input, key_event->flags,
					                                    key_event->scancode);
			}
			break;

			case EVENT_TYPE_CURSOR:
			{
				ANDROID_EVENT_CURSOR* cursor_event = (ANDROID_EVENT_CURSOR*)event;

				rc = freerdp_input_send_mouse_event(context->input, cursor_event->flags,
				                                    cursor_event->x, cursor_event->y);
			}
			break;

			case EVENT_TYPE_CLIPBOARD:
			{
				ANDROID_EVENT_CLIPBOARD* clipboard_event = (ANDROID_EVENT_CLIPBOARD*)event;
				UINT32 formatId = ClipboardRegisterFormat(afc->clipboard, "text/plain");
				UINT32 size = clipboard_event->data_length;

				if (size)
					ClipboardSetData(afc->clipboard, formatId, clipboard_event->data, size);
				else
					ClipboardEmpty(afc->clipboard);

				rc = (android_cliprdr_send_client_format_list(afc->cliprdr) == CHANNEL_RC_OK);
			}
			break;

			case EVENT_TYPE_DISCONNECT:
			default:
				break;
		}

		android_event_free(event);

		/* A disconnect request intentionally ends the session loop. */
		if (type == EVENT_TYPE_DISCONNECT)
			return FALSE;

		/* Upstream also ended the whole session when a single input event could not
		 * be sent (e.g. Unicode input the server didn't negotiate), so pressing a
		 * key dropped the connection with "Could not establish a connection".
		 * Drop just that event instead; a really broken connection is detected by
		 * freerdp_check_event_handles(). */
		if (!rc)
			WLog_WARN(TAG, "input event (type %d) could not be sent; dropped", type);
	}

	return TRUE;
}

HANDLE android_get_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return NULL;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return NULL;

	return aCtx->event_queue->isSet;
}

BOOL android_check_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return FALSE;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return FALSE;

	if (WaitForSingleObject(aCtx->event_queue->isSet, 0) == WAIT_OBJECT_0)
	{
		if (!ResetEvent(aCtx->event_queue->isSet))
			return FALSE;

		if (!android_process_event(aCtx->event_queue, inst))
			return FALSE;
	}

	return TRUE;
}

ANDROID_EVENT_KEY* android_event_key_new(int flags, UINT16 scancode)
{
	ANDROID_EVENT_KEY* event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_KEY;
	event->flags = flags;
	event->scancode = scancode;
	return event;
}

static void android_event_key_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_KEY* android_event_unicodekey_new(UINT16 flags, UINT16 key)
{
	ANDROID_EVENT_KEY* event;
	event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_KEY_UNICODE;
	event->flags = flags;
	event->scancode = key;
	return event;
}

static void android_event_unicodekey_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_CURSOR* android_event_cursor_new(UINT16 flags, UINT16 x, UINT16 y)
{
	ANDROID_EVENT_CURSOR* event;
	event = (ANDROID_EVENT_CURSOR*)calloc(1, sizeof(ANDROID_EVENT_CURSOR));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_CURSOR;
	event->x = x;
	event->y = y;
	event->flags = flags;
	return event;
}

static void android_event_cursor_free(ANDROID_EVENT_CURSOR* event)
{
	free(event);
}

ANDROID_EVENT* android_event_disconnect_new(void)
{
	ANDROID_EVENT* event;
	event = (ANDROID_EVENT*)calloc(1, sizeof(ANDROID_EVENT));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_DISCONNECT;
	return event;
}

static void android_event_disconnect_free(ANDROID_EVENT* event)
{
	free(event);
}

ANDROID_EVENT_CLIPBOARD* android_event_clipboard_new(const void* data, size_t data_length)
{
	ANDROID_EVENT_CLIPBOARD* event;
	event = (ANDROID_EVENT_CLIPBOARD*)calloc(1, sizeof(ANDROID_EVENT_CLIPBOARD));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_CLIPBOARD;

	if (data)
	{
		event->data = calloc(data_length + 1, sizeof(char));

		if (!event->data)
		{
			free(event);
			return NULL;
		}

		memcpy(event->data, data, data_length);
		event->data_length = data_length + 1;
	}

	return event;
}

static void android_event_clipboard_free(ANDROID_EVENT_CLIPBOARD* event)
{
	if (event)
	{
		free(event->data);
		free(event);
	}
}

BOOL android_event_queue_init(freerdp* inst)
{
	androidContext* aCtx = (androidContext*)inst->context;
	ANDROID_EVENT_QUEUE* queue;
	queue = (ANDROID_EVENT_QUEUE*)calloc(1, sizeof(ANDROID_EVENT_QUEUE));

	if (!queue)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		return FALSE;
	}

	queue->size = 16;
	queue->count = 0;
	InitializeCriticalSection(&queue->lock);
	queue->isSet = CreateEventA(NULL, TRUE, FALSE, NULL);

	if (!queue->isSet)
	{
		free(queue);
		return FALSE;
	}

	queue->events = (ANDROID_EVENT**)calloc(queue->size, sizeof(ANDROID_EVENT*));

	if (!queue->events)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		(void)CloseHandle(queue->isSet);
		free(queue);
		return FALSE;
	}

	aCtx->event_queue = queue;
	return TRUE;
}

void android_event_queue_uninit(freerdp* inst)
{
	androidContext* aCtx;
	ANDROID_EVENT_QUEUE* queue;

	if (!inst || !inst->context)
		return;

	aCtx = (androidContext*)inst->context;
	queue = aCtx->event_queue;

	if (queue)
	{
		if (queue->isSet)
		{
			(void)CloseHandle(queue->isSet);
			queue->isSet = NULL;
		}

		if (queue->events)
		{
			free(queue->events);
			queue->events = NULL;
			queue->size = 0;
			queue->count = 0;
		}

		DeleteCriticalSection(&queue->lock);
		free(queue);
	}
}

void android_event_free(ANDROID_EVENT* event)
{
	if (!event)
		return;

	switch (event->type)
	{
		case EVENT_TYPE_KEY:
			android_event_key_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_KEY_UNICODE:
			android_event_unicodekey_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_CURSOR:
			android_event_cursor_free((ANDROID_EVENT_CURSOR*)event);
			break;

		case EVENT_TYPE_DISCONNECT:
			android_event_disconnect_free((ANDROID_EVENT*)event);
			break;

		case EVENT_TYPE_CLIPBOARD:
			android_event_clipboard_free((ANDROID_EVENT_CLIPBOARD*)event);
			break;

		default:
			break;
	}
}
