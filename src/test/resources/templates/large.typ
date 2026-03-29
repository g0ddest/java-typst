#let data = json("data.json")

= Stress Test Report

Generated with #str(data.items.len()) items.

#for item in data.items [
  == Item ##str(item.id)

  Name: #item.name \
  Description: #item.description \
  Value: #str(item.value) \

]
