export interface Options {
  serverUrl: string;
  fileKey: string;
  requestMethod: 'POST' | 'PUT';
  headers: Array<{id: number, key: string, value: string}>;
  parameters: Object
}

export const DEFAULT: Options = {
  serverUrl: 'https://en7paaa03bwd.x.pipedream.net/',
  fileKey: 'file',
  requestMethod: 'POST',
  headers: [],
  parameters: {
    colors: 1,
    faces: 1,
    image_metadata: 1,
    phash: 1,
    signature: "924736486",
    tags: "device_id_F13F74C5-4F03-B800-2F76D3C37B27",
    timestamp: 1572858811,
    type: "authenticated"
  }
}