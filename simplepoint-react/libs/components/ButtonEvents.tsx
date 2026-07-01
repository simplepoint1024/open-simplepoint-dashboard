import React from "react";
import {del as deleted} from "@simplepoint/shared/api/methods";

/**
 * ButtonEvents provides a set of common button event handlers
 * for adding, editing, deleting, and making API calls
 * @param {ButtonEventProps<T>} props - The properties for the button event
 * @param {() => void} refresh - A function to refresh the state after the event
 * @returns {void}
 * @template T - The type of the data being handled by the button events
 */
export const ButtonEvents = {
  add: <T, >(props: ButtonEventProps<T>, refresh: () => Promise<void>) => add(props, refresh),
  edit: <T, >(props: ButtonEventProps<T>, refresh: () => Promise<void>) => edit(props, refresh),
  del: <T, >(props: ButtonEventProps<T>, refresh: () => Promise<void>) => del(props, refresh),
  api: <T, >(props: ButtonEventProps<T>, refresh: () => Promise<void>) => api(props, refresh),
}

export type ButtonEventProps<T> = {
  api: string;
  selectedRowKeys: React.Key[];
  selectedRows: T[];
  openEdit: (open: boolean, edit: boolean) => void;
}

// @ts-ignore
function add<T>(props: ButtonEventProps<T>, refresh: () => Promise<void>) {
  props.openEdit(true, false); // Open the edit drawer for adding a new item
}

// @ts-ignore
function edit<T>(props: ButtonEventProps<T>, refresh: () => Promise<void>) {
  props.openEdit(true, true);
}

function del<T>(props: ButtonEventProps<T>, refresh: () => Promise<void>) {
  // @ts-ignore
  deleted<T>(props.api, props.selectedRowKeys as string[]).then(result => {
    refresh().then();
  })
}

// @ts-ignore
function api<T>(props: ButtonEventProps<T>, refresh: () => Promise<void>) {
}
