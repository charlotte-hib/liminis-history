// Shapes of the JSON returned by the Spring Boot API, generated from api/openapi.yaml into lib/api-schema.ts
// (`npm run api:types`). These names are what the components import.
import type { components } from "@/lib/api-schema";

type Schemas = components["schemas"];

export type Language = Schemas["Language"];
export type Domain = Schemas["Domain"];
export type Region = Schemas["Region"];
export type Era = Schemas["Era"];
export type Person = Schemas["Person"];
export type Life = Schemas["Life"];
export type LifeIcon = Schemas["LifeIcon"];
export type Participant = Schemas["Participant"];
export type Event = Schemas["Event"];
export type EraDetail = Schemas["EraDetail"];
export type Artwork = Schemas["Artwork"];
export type WorldAround = Schemas["WorldAround"];
export type MomentSummary = Schemas["MomentSummary"];
export type Door = Schemas["Door"];
export type MomentDetail = Schemas["MomentDetail"];
export type MomentPeople = Schemas["MomentPeople"];
export type StoryCard = Schemas["StoryCard"];
export type Story = Schemas["Story"];
export type LifeLine = Schemas["LifeLine"];
export type AnecdoteSource = Schemas["AnecdoteSource"];
export type PersonPlace = Schemas["PersonPlace"];
export type PlaceKind = Schemas["PlaceKind"];
export type PlaceRole = Schemas["PlaceRole"];
export type Connection = Schemas["Connection"];
export type PersonDetail = Schemas["PersonDetail"];
export type SearchResults = Schemas["SearchResults"];
