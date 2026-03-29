#let data = json("data.json")
#let company = data.company
#let address = company.address

= Company: #company.name

Address: #address.city, #address.street

#for dept in company.departments [
  - #dept.name (#str(dept.headcount) employees)
]
