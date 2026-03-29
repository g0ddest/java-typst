#let data = json("data.json")
#let items = data.items

= #data.title

#table(
  columns: (1fr, auto, auto),
  table.header([*Name*], [*Qty*], [*Price*]),
  ..items.map(item => (item.name, str(item.qty), str(item.price))).flatten()
)
